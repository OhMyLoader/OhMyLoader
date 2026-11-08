package org.ohmyloader.core.expression.pool;

import org.ohmyloader.core.expression.flow.FlowValue;

public interface MemberDefinition {
    boolean matches(FlowValue node);
}
