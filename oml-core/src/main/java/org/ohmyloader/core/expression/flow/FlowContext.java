package org.ohmyloader.core.expression.flow;

import org.objectweb.asm.Type;

/**
 * The class-hierarchy knowledge the value-flow analysis needs: merging two verification types that
 * are neither identical nor int-like requires their **common supertype**, which is only decidable
 * with the target class's loader. OML supplies an implementation backed by the loader that is
 * defining the class; tests can supply a fixed one.
 */
public interface FlowContext {
    /**
     * The common supertype of two object types. Called only for two non-array, non-int-like object
     * sorts; the caller has already handled every type shape that needs no hierarchy lookup.
     */
    Type getCommonSupertype(Type type1, Type type2);
}
