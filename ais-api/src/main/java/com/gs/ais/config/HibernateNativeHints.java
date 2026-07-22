package com.gs.ais.config;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Hibernate 7's JBoss Logging implementations are loaded by class name at
 * runtime. Register the generated *_$logger classes for GraalVM Native Image.
 */
public final class HibernateNativeHints implements RuntimeHintsRegistrar {

	private static final List<String> DIALECT_OVERRIDE_ANNOTATIONS = List.of(
		"org.hibernate.annotations.DialectOverride",
		"org.hibernate.annotations.DialectOverride$Check",
		"org.hibernate.annotations.DialectOverride$Checks",
		"org.hibernate.annotations.DialectOverride$ColumnDefault",
		"org.hibernate.annotations.DialectOverride$ColumnDefaults",
		"org.hibernate.annotations.DialectOverride$DiscriminatorFormula",
		"org.hibernate.annotations.DialectOverride$DiscriminatorFormulas",
		"org.hibernate.annotations.DialectOverride$FilterDefOverrides",
		"org.hibernate.annotations.DialectOverride$FilterDefs",
		"org.hibernate.annotations.DialectOverride$FilterOverrides",
		"org.hibernate.annotations.DialectOverride$Filters",
		"org.hibernate.annotations.DialectOverride$Formula",
		"org.hibernate.annotations.DialectOverride$Formulas",
		"org.hibernate.annotations.DialectOverride$GeneratedColumn",
		"org.hibernate.annotations.DialectOverride$GeneratedColumns",
		"org.hibernate.annotations.DialectOverride$JoinFormula",
		"org.hibernate.annotations.DialectOverride$JoinFormulas",
		"org.hibernate.annotations.DialectOverride$OverridesAnnotation",
		"org.hibernate.annotations.DialectOverride$SQLDelete",
		"org.hibernate.annotations.DialectOverride$SQLDeleteAll",
		"org.hibernate.annotations.DialectOverride$SQLDeleteAlls",
		"org.hibernate.annotations.DialectOverride$SQLDeletes",
		"org.hibernate.annotations.DialectOverride$SQLInsert",
		"org.hibernate.annotations.DialectOverride$SQLInserts",
		"org.hibernate.annotations.DialectOverride$SQLOrder",
		"org.hibernate.annotations.DialectOverride$SQLOrders",
		"org.hibernate.annotations.DialectOverride$SQLRestriction",
		"org.hibernate.annotations.DialectOverride$SQLRestrictions",
		"org.hibernate.annotations.DialectOverride$SQLSelect",
		"org.hibernate.annotations.DialectOverride$SQLSelects",
		"org.hibernate.annotations.DialectOverride$SQLUpdate",
		"org.hibernate.annotations.DialectOverride$SQLUpdates",
		"org.hibernate.annotations.DialectOverride$Version"
	);

	private static final List<String> EVENT_LISTENER_ARRAYS = List.of(
		"org.hibernate.event.spi.LoadEventListener[]",
		"org.hibernate.event.spi.InitializeCollectionEventListener[]",
		"org.hibernate.event.spi.PersistEventListener[]",
		"org.hibernate.event.spi.MergeEventListener[]",
		"org.hibernate.event.spi.DeleteEventListener[]",
		"org.hibernate.event.spi.ReplicateEventListener[]",
		"org.hibernate.event.spi.FlushEventListener[]",
		"org.hibernate.event.spi.AutoFlushEventListener[]",
		"org.hibernate.event.spi.PreFlushEventListener[]",
		"org.hibernate.event.spi.DirtyCheckEventListener[]",
		"org.hibernate.event.spi.FlushEntityEventListener[]",
		"org.hibernate.event.spi.ClearEventListener[]",
		"org.hibernate.event.spi.EvictEventListener[]",
		"org.hibernate.event.spi.LockEventListener[]",
		"org.hibernate.event.spi.RefreshEventListener[]",
		"org.hibernate.event.spi.PreLoadEventListener[]",
		"org.hibernate.event.spi.PreDeleteEventListener[]",
		"org.hibernate.event.spi.PreUpdateEventListener[]",
		"org.hibernate.event.spi.PreUpsertEventListener[]",
		"org.hibernate.event.spi.PreInsertEventListener[]",
		"org.hibernate.event.spi.PostLoadEventListener[]",
		"org.hibernate.event.spi.PostDeleteEventListener[]",
		"org.hibernate.event.spi.PostUpdateEventListener[]",
		"org.hibernate.event.spi.PostUpsertEventListener[]",
		"org.hibernate.event.spi.PostInsertEventListener[]",
		"org.hibernate.event.spi.PreCollectionRecreateEventListener[]",
		"org.hibernate.event.spi.PreCollectionRemoveEventListener[]",
		"org.hibernate.event.spi.PreCollectionUpdateEventListener[]",
		"org.hibernate.event.spi.PostCollectionRecreateEventListener[]",
		"org.hibernate.event.spi.PostCollectionRemoveEventListener[]",
		"org.hibernate.event.spi.PostCollectionUpdateEventListener[]"
	);

	private static final List<String> LOGGER_IMPLEMENTATIONS = List.of(
		"org.hibernate.action.internal.ActionLogging_$logger",
		"org.hibernate.boot.BootLogging_$logger",
		"org.hibernate.boot.archive.scan.internal.ScannerLogger_$logger",
		"org.hibernate.boot.beanvalidation.BeanValidationLogger_$logger",
		"org.hibernate.boot.jaxb.JaxbLogger_$logger",
		"org.hibernate.bytecode.enhance.internal.BytecodeEnhancementLogging_$logger",
		"org.hibernate.bytecode.enhance.spi.interceptor.BytecodeInterceptorLogging_$logger",
		"org.hibernate.cache.spi.SecondLevelCacheLogger_$logger",
		"org.hibernate.collection.internal.CollectionLogger_$logger",
		"org.hibernate.context.internal.CurrentSessionLogging_$logger",
		"org.hibernate.dialect.DialectLogging_$logger",
		"org.hibernate.engine.internal.NaturalIdLogging_$logger",
		"org.hibernate.engine.internal.PersistenceContextLogging_$logger",
		"org.hibernate.engine.internal.SessionMetricsLogger_$logger",
		"org.hibernate.engine.internal.VersionLogger_$logger",
		"org.hibernate.engine.jdbc.JdbcLogging_$logger",
		"org.hibernate.engine.jdbc.batch.JdbcBatchLogging_$logger",
		"org.hibernate.engine.jdbc.connections.internal.ConnectionProviderLogging_$logger",
		"org.hibernate.engine.jdbc.env.internal.LobCreationLogging_$logger",
		"org.hibernate.engine.jdbc.spi.SQLExceptionLogging_$logger",
		"org.hibernate.event.internal.EntityCopyLogging_$logger",
		"org.hibernate.event.internal.EventListenerLogging_$logger",
		"org.hibernate.id.UUIDLogger_$logger",
		"org.hibernate.id.enhanced.OptimizerLogger_$logger",
		"org.hibernate.id.enhanced.SequenceGeneratorLogger_$logger",
		"org.hibernate.id.enhanced.TableGeneratorLogger_$logger",
		"org.hibernate.internal.CoreMessageLogger_$logger",
		"org.hibernate.internal.SessionFactoryLogging_$logger",
		"org.hibernate.internal.SessionFactoryRegistryMessageLogger_$logger",
		"org.hibernate.internal.SessionLogging_$logger",
		"org.hibernate.internal.log.ConnectionAccessLogger_$logger",
		"org.hibernate.internal.log.ConnectionInfoLogger_$logger",
		"org.hibernate.internal.log.DeprecationLogger_$logger",
		"org.hibernate.internal.log.IncubationLogger_$logger",
		"org.hibernate.internal.log.StatisticsLogger_$logger",
		"org.hibernate.internal.log.UrlMessageBundle_$logger",
		"org.hibernate.jpa.internal.JpaLogger_$logger",
		"org.hibernate.loader.ast.internal.MultiKeyLoadLogging_$logger",
		"org.hibernate.metamodel.mapping.MappingModelCreationLogging_$logger",
		"org.hibernate.query.QueryLogging_$logger",
		"org.hibernate.query.hql.HqlLogging_$logger",
		"org.hibernate.resource.beans.internal.BeansMessageLogger_$logger",
		"org.hibernate.resource.jdbc.internal.LogicalConnectionLogging_$logger",
		"org.hibernate.resource.jdbc.internal.ResourceRegistryLogger_$logger",
		"org.hibernate.resource.transaction.backend.jta.internal.JtaLogging_$logger",
		"org.hibernate.resource.transaction.internal.SynchronizationLogging_$logger",
		"org.hibernate.service.internal.ServiceLogger_$logger",
		"org.hibernate.sql.ast.tree.SqlAstTreeLogger_$logger",
		"org.hibernate.sql.exec.SqlExecLogger_$logger",
		"org.hibernate.sql.model.ModelMutationLogging_$logger",
		"org.hibernate.sql.results.LoadingLogger_$logger",
		"org.hibernate.sql.results.ResultsLogger_$logger",
		"org.hibernate.sql.results.graph.embeddable.EmbeddableLoadingLogger_$logger"
	);

	private void registerAnnotationImplementations(RuntimeHints hints, ClassLoader classLoader) {
		final String packagePath = "org/hibernate/boot/models/annotations/internal/";
		try {
			Enumeration<URL> resources = classLoader.getResources(packagePath.substring(0, packagePath.length() - 1));
			while (resources.hasMoreElements()) {
				URL resource = resources.nextElement();
				if (!(resource.openConnection() instanceof JarURLConnection connection)) {
					continue;
				}
				try (JarFile jar = connection.getJarFile()) {
					Enumeration<JarEntry> entries = jar.entries();
					while (entries.hasMoreElements()) {
						String entryName = entries.nextElement().getName();
						if (!entryName.startsWith(packagePath) || !entryName.endsWith(".class")
								|| entryName.contains("$")) {
							continue;
						}
						String typeName = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
						hints.reflection().registerType(
							TypeReference.of(typeName),
							MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
							MemberCategory.INVOKE_DECLARED_METHODS,
							MemberCategory.DECLARED_FIELDS
						);
					}
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to register Hibernate annotation implementations", ex);
		}
	}

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		// Ensure /api/version can read build metadata in GraalVM Native Image.
		hints.resources().registerPattern("META-INF/build-info.properties");
		hints.resources().registerPattern("version.properties");
		registerAnnotationImplementations(hints, classLoader);
		for (String annotationType : DIALECT_OVERRIDE_ANNOTATIONS) {
			hints.reflection().registerType(
				TypeReference.of(annotationType),
				MemberCategory.DECLARED_CLASSES,
				MemberCategory.INTROSPECT_DECLARED_METHODS,
				MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS
			);
		}
		for (String eventListenerArray : EVENT_LISTENER_ARRAYS) {
			hints.reflection().registerType(TypeReference.of(eventListenerArray), builder -> { });
		}
		for (String strategyImplementation : List.of(
			"org.hibernate.boot.model.relational.ColumnOrderingStrategyStandard",
			"org.hibernate.community.dialect.SQLiteDialect",
			"org.hibernate.dialect.MySQLDialect"
		)) {
			hints.reflection().registerType(
				TypeReference.of(strategyImplementation),
				MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
				MemberCategory.INVOKE_DECLARED_METHODS
			);
		}
		for (String loggerImplementation : LOGGER_IMPLEMENTATIONS) {
			hints.reflection().registerType(
				TypeReference.of(loggerImplementation),
				MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
				MemberCategory.INVOKE_DECLARED_METHODS
			);
		}
	}

}
