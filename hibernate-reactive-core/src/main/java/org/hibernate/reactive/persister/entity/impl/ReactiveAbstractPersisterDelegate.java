/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.persister.entity.impl;

import java.lang.invoke.MethodHandles;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.hibernate.AssertionFailure;
import org.hibernate.LockOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.id.IdentityGenerator;
import org.hibernate.loader.ast.spi.MultiIdLoadOptions;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.SingularAttributeMapping;
import org.hibernate.metamodel.mapping.internal.GeneratedValuesProcessor;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.query.named.NamedQueryMemento;
import org.hibernate.reactive.loader.ast.internal.MultiIdLoaderStandard;
import org.hibernate.reactive.loader.ast.internal.ReactiveSingleIdEntityLoaderDynamicBatch;
import org.hibernate.reactive.loader.ast.internal.ReactiveSingleIdEntityLoaderProvidedQueryImpl;
import org.hibernate.reactive.loader.ast.internal.ReactiveSingleIdEntityLoaderStandardImpl;
import org.hibernate.reactive.loader.ast.internal.ReactiveSingleUniqueKeyEntityLoaderStandard;
import org.hibernate.reactive.loader.ast.spi.ReactiveMultiIdEntityLoader;
import org.hibernate.reactive.loader.ast.spi.ReactiveSingleIdEntityLoader;
import org.hibernate.reactive.loader.ast.spi.ReactiveSingleUniqueKeyEntityLoader;
import org.hibernate.reactive.logging.impl.Log;
import org.hibernate.reactive.logging.impl.LoggerFactory;

import static org.hibernate.reactive.util.impl.CompletionStages.voidFuture;

public class ReactiveAbstractPersisterDelegate {

	private static final Log LOG = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	private final ReactiveSingleIdEntityLoader<Object> singleIdEntityLoader;
	private final ReactiveMultiIdEntityLoader<?> multiIdEntityLoader;

	private Map<SingularAttributeMapping, ReactiveSingleUniqueKeyEntityLoader<Object>> uniqueKeyLoadersNew;

	// FIXME: not yet initialized
	private GeneratedValuesProcessor insertGeneratedValuesProcessor;
	private GeneratedValuesProcessor updateGeneratedValuesProcessor;

	public ReactiveAbstractPersisterDelegate(
			final EntityPersister entityDescriptor,
			final PersistentClass persistentClass,
			final RuntimeModelCreationContext creationContext) {
		SessionFactoryImplementor factory = creationContext.getSessionFactory();
		singleIdEntityLoader = createReactiveSingleIdEntityLoader( entityDescriptor, persistentClass, creationContext, factory, entityDescriptor.getEntityName() );
		multiIdEntityLoader = new MultiIdLoaderStandard<>( entityDescriptor, persistentClass, factory );
	}

	public ReactiveSingleIdEntityLoader<Object> getSingleIdEntityLoader() {
		return singleIdEntityLoader;
	}

	public CompletionStage<? extends List<?>> multiLoad(Object[] ids, SharedSessionContractImplementor session, MultiIdLoadOptions loadOptions) {
		return multiIdEntityLoader.load( ids, loadOptions, session );
	}

	private static ReactiveSingleIdEntityLoader<Object> createReactiveSingleIdEntityLoader(
			EntityMappingType entityDescriptor,
			PersistentClass bootDescriptor,
			RuntimeModelCreationContext creationContext,
			SessionFactoryImplementor factory,
			String entityName) {
		int batchSize = batchSize( bootDescriptor, factory );
		if ( bootDescriptor.getLoaderName() != null ) {
			// We must resolve the named query on-demand through the boot model because it isn't initialized yet
			final NamedQueryMemento namedQueryMemento = factory.getQueryEngine().getNamedObjectRepository()
					.resolve( factory, creationContext.getBootModel(), bootDescriptor.getLoaderName() );
			if ( namedQueryMemento == null ) {
				throw new IllegalArgumentException( "Could not resolve named load-query [" + entityName + "] : " + bootDescriptor.getLoaderName() );
			}
			return new ReactiveSingleIdEntityLoaderProvidedQueryImpl<>( entityDescriptor, namedQueryMemento );
		}

		if ( batchSize > 1 ) {
			return new ReactiveSingleIdEntityLoaderDynamicBatch<>( entityDescriptor, batchSize, factory );
		}

		return new ReactiveSingleIdEntityLoaderStandardImpl<>( entityDescriptor, factory );
	}

	private static int batchSize(PersistentClass bootDescriptor, SessionFactoryImplementor factory) {
		int batchSize = bootDescriptor.getBatchSize();
		if ( batchSize == -1 ) {
			batchSize = factory.getSessionFactoryOptions().getDefaultBatchFetchSize();
		}
		return batchSize;
	}

	public CompletionStage<Void> processInsertGeneratedProperties(Object id, Object entity, Object[] state, SharedSessionContractImplementor session, String entityName) {
		if ( insertGeneratedValuesProcessor == null ) {
			throw new UnsupportedOperationException( "Entity has no insert-generated properties - `" + entityName + "`" );
		}
		return voidFuture()
				.thenAccept( v -> insertGeneratedValuesProcessor.processGeneratedValues( entity, id, state, session ) );
	}

	public CompletionStage<Void> processUpdateGeneratedProperties(Object id, Object entity, Object[] state, SharedSessionContractImplementor session, String entityName) {
		if ( updateGeneratedValuesProcessor == null ) {
			throw new AssertionFailure( "Entity has no update-generated properties - `" + entityName + "`" );
		}
		return voidFuture()
				.thenAccept( v -> updateGeneratedValuesProcessor.processGeneratedValues( entity, id, state, session ) );
	}

	public GeneratedValuesProcessor getInsertGeneratedValuesProcessor() {
		return insertGeneratedValuesProcessor;
	}

	public GeneratedValuesProcessor getUpdateGeneratedValuesProcessor() {
		return updateGeneratedValuesProcessor;
	}

	public Map<SingularAttributeMapping, ReactiveSingleUniqueKeyEntityLoader<Object>> getUniqueKeyLoadersNew() {
		return uniqueKeyLoadersNew;
	}

	protected ReactiveSingleUniqueKeyEntityLoader<Object> getReactiveUniqueKeyLoader(EntityPersister entityDescriptor, SingularAttributeMapping attribute) {
		if ( uniqueKeyLoadersNew == null ) {
			uniqueKeyLoadersNew = new IdentityHashMap<>();
		}
		return uniqueKeyLoadersNew
				.computeIfAbsent( attribute, key -> new ReactiveSingleUniqueKeyEntityLoaderStandard<>( entityDescriptor, key ) );
	}

	public CompletionStage<Object> load(EntityPersister persister, Object id, Object optionalObject, LockOptions lockOptions, Boolean readOnly, SharedSessionContractImplementor session) {
		if ( LOG.isTraceEnabled() ) {
			LOG.tracev( "Fetching entity: {0}", MessageHelper.infoString( persister, id, persister.getFactory() ) );
		}
		return optionalObject == null
				? singleIdEntityLoader.load( id, lockOptions, readOnly, session )
				: singleIdEntityLoader.load( id, optionalObject, lockOptions, readOnly, session );
	}

	// FIXME: Is this correct?
	public IdentifierGenerator reactive(IdentifierGenerator identifierGenerator) {
		if ( identifierGenerator instanceof IdentityGenerator ) {
			return new ReactiveIdentityGenerator();
		}
		return identifierGenerator;
	}
}
