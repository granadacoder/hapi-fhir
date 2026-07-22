/*-
 * #%L
 * HAPI FHIR JPA Model
 * %%
 * Copyright (C) 2014 - 2026 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.model.dialect;

import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.model.entity.StorageSettings;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.util.ISequenceValueMassager;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.hapi.fhir.sql.hibernatesvc.HapiHibernateDialectSettingsService;
import ca.uhn.hapi.fhir.sql.hibernatesvc.IdSequencePoolingStrategy;
import org.apache.commons.lang3.Validate;
import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.ExportableProducer;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.GeneratorCreationContext;
import org.hibernate.id.BulkInsertionCapableIdentifierGenerator;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.id.OptimizableGenerator;
import org.hibernate.id.PersistentIdentifierGenerator;
import org.hibernate.id.enhanced.Optimizer;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.hibernate.id.enhanced.StandardOptimizerDescriptor;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.Value;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.Type;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.function.Supplier;

import static ca.uhn.fhir.jpa.model.util.JpaConstants.NO_MORE_PID;

/**
 * This is a sequence generator that wraps the Hibernate default sequence generator {@link SequenceStyleGenerator}
 * and by default will therefore work exactly as the default would, but allows for customization.
 */
@SuppressWarnings("unused")
public class HapiSequenceStyleGenerator
		implements PersistentIdentifierGenerator, BulkInsertionCapableIdentifierGenerator, ExportableProducer {
	public static final String ID_MASSAGER_TYPE_KEY = "hapi_fhir.sequence_generator_massager";
	private final SequenceStyleGenerator myGen = new SequenceStyleGenerator();

	@Autowired
	private StorageSettings myStorageSettings;

	private ISequenceValueMassager myIdMassager;
	private boolean myConfigured;
	private String myGeneratorName;

	@Override
	public boolean supportsBulkInsertionIdentifierGeneration() {
		return myGen.supportsBulkInsertionIdentifierGeneration();
	}

	@Override
	public String determineBulkInsertionIdentifierGenerationSelectFragment(SqlStringGenerationContext theContext) {
		return myGen.determineBulkInsertionIdentifierGenerationSelectFragment(theContext);
	}

	@Override
	public Serializable generate(SharedSessionContractImplementor theSession, Object theObject)
			throws HibernateException {
		return generateNonReservedValue(() -> doGenerate(theSession, theObject));
	}

	/**
	 * Pulls an id from the supplied id source, guarding against the reserved {@link JpaConstants#NO_MORE_PID} value which
	 * has a special internal meaning to HAPI and must never be persisted as a resource id. This should never
	 * happen since the sequence starts at 1, but if someone ever manually messes with sequences or the
	 * sequence otherwise gets messed up, we retry once and then fail rather than persisting the reserved value.
	 */
	static Long generateNonReservedValue(Supplier<Long> theIdSupplier) {
		Long nextVal = theIdSupplier.get();
		if (NO_MORE_PID.equals(nextVal)) {
			// retry once
			nextVal = theIdSupplier.get();
		}

		if (NO_MORE_PID.equals(nextVal)) {
			// fail if we're stuck here.
			throw new InternalErrorException(
					Msg.code(2791) + "Resource ID generator provided illegal value: " + nextVal + " / " + nextVal);
		}
		return nextVal;
	}

	private Long doGenerate(SharedSessionContractImplementor theSession, Object theObject) {
		Long retVal = myIdMassager != null ? myIdMassager.generate(myGeneratorName) : null;
		if (retVal == null) {
			Long next = (Long) myGen.generate(theSession, theObject);

			retVal = myIdMassager.massage(myGeneratorName, next);
		}
		return retVal;
	}

	@Override
	public void configure(Type theType, Properties theParams, ServiceRegistry theServiceRegistry)
			throws MappingException {
		configure(theType, theParams, theServiceRegistry, null);
	}

	@Override
	public void configure(GeneratorCreationContext theContext, Properties theParams) throws MappingException {
		configure(theContext.getType(), theParams, theContext.getServiceRegistry(), theContext);
	}

	private void configure(
			Type theType, Properties theParams, ServiceRegistry theServiceRegistry, GeneratorCreationContext theContext)
			throws MappingException {

		myIdMassager = theServiceRegistry.getService(ISequenceValueMassager.class);
		if (myIdMassager == null) {
			myIdMassager = new ISequenceValueMassager.NoopSequenceValueMassager();
		}

		// Create a HAPI FHIR sequence style generator
		myGeneratorName = theParams.getProperty(IdentifierGenerator.GENERATOR_NAME);
		Validate.notBlank(myGeneratorName, "No generator name found");

		Properties props = new Properties(theParams);

		// The legacy pooled optimizer (SHARED_POOL, the default) keeps a single node-wide id pool behind a
		// lock. The opt-in thread-local optimizer (PER_THREAD_POOL / pooled-lotl) keeps the pool in a
		// ThreadLocal so concurrent writers each allocate from their own block instead of serializing on that
		// lock during refills. Initial value is kept larger than the increment for backwards-compatible schema
		// export.
		IdSequencePoolingStrategy poolingStrategy = determineIdSequencePoolingStrategy(theServiceRegistry);
		props.put(OptimizableGenerator.OPT_PARAM, determineOptimizerExternalName(poolingStrategy));
		props.put(OptimizableGenerator.INITIAL_PARAM, 1000);
		props.put(OptimizableGenerator.INCREMENT_PARAM, 50);
		props.put(IdentifierGenerator.GENERATOR_NAME, myGeneratorName);

		GeneratorCreationContext generatorCreationContext =
				theContext != null ? theContext : createGeneratorCreationContext(theType, theServiceRegistry);
		configureSequenceStyleGenerator(theType, theServiceRegistry, props, generatorCreationContext);

		myConfigured = true;
	}

	private void configureSequenceStyleGenerator(
			Type theType,
			ServiceRegistry theServiceRegistry,
			Properties theProperties,
			GeneratorCreationContext theGeneratorCreationContext) {
		try {
			try {
				Method configureWithContext = SequenceStyleGenerator.class.getMethod(
						"configure", GeneratorCreationContext.class, Properties.class);
				configureWithContext.invoke(myGen, theGeneratorCreationContext, theProperties);
				return;
			} catch (NoSuchMethodException e) {
				// Hibernate ORM < 7.4 uses the legacy configure(Type, Properties, ServiceRegistry) signature.
			}

			Method configureLegacy = SequenceStyleGenerator.class.getMethod(
					"configure", Type.class, Properties.class, ServiceRegistry.class);
			configureLegacy.invoke(myGen, theType, theProperties, theServiceRegistry);
		} catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
			Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new MappingException("Failed to configure SequenceStyleGenerator", cause);
		}
	}

	private static GeneratorCreationContext createGeneratorCreationContext(
			Type theType, ServiceRegistry theServiceRegistry) {
		Value value = (Value) Proxy.newProxyInstance(
				HapiSequenceStyleGenerator.class.getClassLoader(),
				new Class<?>[] {Value.class},
				new InvocationHandler() {
					@Override
					public Object invoke(Object theProxy, Method theMethod, Object[] theArgs) {
						return switch (theMethod.getName()) {
							case "getTable" -> new Table("HAPI_FHIR_SEQUENCE_GENERATOR");
							case "getType" -> theType;
							case "getServiceRegistry" -> theServiceRegistry;
							case "isSimpleValue" -> true;
							case "isValid" -> true;
							case "isNullable" -> false;
							case "hasColumns",
									"hasFormula",
									"isAlternateUniqueKey",
									"isPartitionKey",
									"hasAnyInsertableColumns",
									"hasAnyUpdatableColumns" -> false;
							default -> null;
						};
					}
				});

		return new GeneratorCreationContext() {
			@Override
			public org.hibernate.boot.model.relational.Database getDatabase() {
				return null;
			}

			@Override
			public ServiceRegistry getServiceRegistry() {
				return theServiceRegistry;
			}

			@Override
			public String getDefaultCatalog() {
				return null;
			}

			@Override
			public String getDefaultSchema() {
				return null;
			}

			@Override
			public org.hibernate.mapping.PersistentClass getPersistentClass() {
				return null;
			}

			@Override
			public org.hibernate.mapping.RootClass getRootClass() {
				return null;
			}

			@Override
			public org.hibernate.mapping.Property getProperty() {
				return null;
			}

			@Override
			public Value getValue() {
				return value;
			}

			@Override
			public Type getType() {
				return theType;
			}
		};
	}

	@Override
	public void registerExportables(Database database) {
		if (!myConfigured) {
			return;
		}
		myGen.registerExportables(database);
	}

	@Override
	public void initialize(SqlStringGenerationContext context) {
		myGen.initialize(context);
	}

	@Override
	public Optimizer getOptimizer() {
		return myGen.getOptimizer();
	}

	/**
	 * Returns the Hibernate optimizer external name to use for the given id-sequence pooling strategy: the
	 * thread-local pooled optimizer for {@link IdSequencePoolingStrategy#PER_THREAD_POOL}, or the legacy single
	 * shared pooled optimizer for {@link IdSequencePoolingStrategy#SHARED_POOL}.
	 */
	static String determineOptimizerExternalName(IdSequencePoolingStrategy theStrategy) {
		StandardOptimizerDescriptor descriptor =
				switch (theStrategy) {
					case PER_THREAD_POOL -> StandardOptimizerDescriptor.POOLED_LOTL;
					case SHARED_POOL -> StandardOptimizerDescriptor.POOLED;
				};
		return descriptor.getExternalName();
	}

	/**
	 * Reads the id-sequence pooling strategy from the {@link HapiHibernateDialectSettingsService} registered in
	 * the Hibernate {@link ServiceRegistry}. Defaults to {@link IdSequencePoolingStrategy#SHARED_POOL} (the
	 * legacy single shared pool) when the service is not registered (e.g. in lightweight bootstrap contexts),
	 * matching the production default.
	 */
	private static IdSequencePoolingStrategy determineIdSequencePoolingStrategy(ServiceRegistry theServiceRegistry) {
		HapiHibernateDialectSettingsService settings =
				theServiceRegistry.getService(HapiHibernateDialectSettingsService.class);
		return settings != null ? settings.getIdSequencePoolingStrategy() : IdSequencePoolingStrategy.SHARED_POOL;
	}
}
