package org.ohmyloader.core.expression.ast.identifiers;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.pool.IdentifierPool;

public interface MemberIdentifier {
    boolean matches(IdentifierPool pool, FlowValue node);
}
