/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.loader.ast.spi;

import java.util.concurrent.CompletionStage;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.loader.ast.spi.SingleIdEntityLoader;

/**
 * Reactive version of {@link SingleIdEntityLoader}.
 * @param <T> the entity class
 */
public interface ReactiveSingleIdEntityLoader<T> extends SingleIdEntityLoader<CompletionStage<T>> {

	/**
	 * @deprecated use {@link #reactiveLoadDatabaseSnapshot(Object, SharedSessionContractImplementor)}
	 */
	@Deprecated
	@Override
	default Object[] loadDatabaseSnapshot(Object id, SharedSessionContractImplementor session) {
		throw new UnsupportedOperationException("Use the reactive method: loadDatabaseSnapshot(Object, SharedSessionContractImplementor)");
	}

	CompletionStage<Object[]> reactiveLoadDatabaseSnapshot(Object id, SharedSessionContractImplementor session);
}
