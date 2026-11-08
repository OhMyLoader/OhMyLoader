package org.ohmyloader.core.expression.flow.postprocessing;

import org.ohmyloader.core.expression.flow.DummyFlowValue;
import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.FlowValue.Edge;

import java.util.ArrayList;

public class SplitNodeRemovalPostProcessor implements FlowPostProcessor {
    @Override
    public void process(FlowValue node, OutputSink sink) {
        if (node.getNext().size() <= 1) {
            // The node is not split.
            return;
        }
        FlowValue successor = new DummyFlowValue(node.getType());
        for (Edge next : new ArrayList<>(node.getNext())) {
            FlowValue target = next.value();
            int index = next.index();
            target.setParent(index, successor);
        }
    }
}
