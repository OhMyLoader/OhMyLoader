package org.ohmyloader.core.expression.pool;

import org.objectweb.asm.Type;

public interface TypeDefinition {
    boolean matches(Type type);
}
