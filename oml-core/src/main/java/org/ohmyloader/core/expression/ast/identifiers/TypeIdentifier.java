package org.ohmyloader.core.expression.ast.identifiers;

import org.ohmyloader.core.expression.pool.IdentifierPool;
import org.objectweb.asm.Type;

public interface TypeIdentifier {
    boolean matches(IdentifierPool pool, Type type);
}
