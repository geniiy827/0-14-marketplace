package com.geniiy.worldbuilder.edit;

import java.util.ArrayList;
import java.util.List;

/** История отмены: список шагов и указатель. Лимиты — по числу шагов и по памяти. */
public class UndoHistory {
    public final List<ChangeSet> steps = new ArrayList<>();
    /** Число применённых шагов (steps[0..pos-1] применены). */
    public int pos;
    public int maxSteps = 200;
    public long maxBytes = 512L << 20;

    public void push(ChangeSet cs) {
        if (cs == null || cs.isEmpty()) return;
        while (steps.size() > pos) steps.remove(steps.size() - 1);
        steps.add(cs);
        pos = steps.size();
        trim();
    }

    private void trim() {
        long total = 0;
        for (ChangeSet c : steps) total += c.memoryBytes();
        while ((steps.size() > maxSteps || total > maxBytes) && steps.size() > 1) {
            total -= steps.get(0).memoryBytes();
            steps.remove(0);
            pos--;
        }
    }

    public boolean canUndo() { return pos > 0; }
    public boolean canRedo() { return pos < steps.size(); }
    public ChangeSet undoStep() { return canUndo() ? steps.get(--pos) : null; }
    public ChangeSet redoStep() { return canRedo() ? steps.get(pos++) : null; }
    public void clear() { steps.clear(); pos = 0; }
    public long memory() { long t = 0; for (ChangeSet c : steps) t += c.memoryBytes(); return t; }
}
