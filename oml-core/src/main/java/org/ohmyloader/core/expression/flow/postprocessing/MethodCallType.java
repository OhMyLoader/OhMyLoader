package org.ohmyloader.core.expression.flow.postprocessing;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.utils.FlowDecorations;

public enum MethodCallType {
    NORMAL,
    SUPER,
    STATIC;

    public boolean matches(FlowValue node) {
        return node.getDecoration(FlowDecorations.METHOD_CALL_TYPE) == this;
    }
}
