package org.mp.naumann.algorithms.fd.hyfd;

import org.apache.lucene.util.OpenBitSet;
import org.mp.naumann.algorithms.fd.FDLogger;
import org.mp.naumann.algorithms.fd.structures.FDSet;
import org.mp.naumann.algorithms.fd.structures.FDTree;

import java.util.List;
import java.util.logging.Level;

class Inductor {

    private FDSet negCover;
    private FDTree posCover;
    private MemoryGuardian memoryGuardian;

    public Inductor(FDSet negCover, FDTree posCover, MemoryGuardian memoryGuardian) {
        this.negCover = negCover;
        this.posCover = posCover;
        this.memoryGuardian = memoryGuardian;
    }

    public void updatePositiveCover(FDList nonFds) {
        FDLogger.log(Level.FINER, "Inducing FD candidates ...");
        // Level means number of set bits, e.g. 01010101 -> Level 4.
        // We start with higher levels because theyre more general
        for (int i = nonFds.getFdLevels().size() - 1; i >= 0; i--) {
            if (i >= nonFds.getFdLevels().size()) // If this level has been trimmed during iteration
                continue;

            List<OpenBitSet> nonFdLevel = nonFds.getFdLevels().get(i);
            for (OpenBitSet lhs : nonFdLevel) {

                // All 0s cannot be on the right-hand side anymore if 01010101 is the LHS.
                // Thus flipping gives us the full rhs
                OpenBitSet fullRhs = lhs.clone();
                fullRhs.flip(0, fullRhs.size());

                // Now we go through all "not-rhs's" and specialize the pos. cover with them.
                for (int rhs = fullRhs.nextSetBit(0); rhs >= 0; rhs = fullRhs.nextSetBit(rhs + 1))
                    this.specializePositiveCover(lhs, rhs, nonFds);
            }
            nonFdLevel.clear();
        }
    }

    private int specializePositiveCover(OpenBitSet lhs, int rhs, FDList nonFds) {
        int numAttributes = this.posCover.getChildren().length;
        int newFDs = 0;
        List<OpenBitSet> specLhss = this.posCover.getFdAndGeneralizations(lhs, rhs);
        if (!(specLhss = this.posCover.getFdAndGeneralizations(lhs, rhs)).isEmpty()) { // TODO: May be "while" instead of "if"?
            for (OpenBitSet specLhs : specLhss) {
                this.posCover.removeFunctionalDependency(specLhs, rhs);

                if ((this.posCover.getMaxDepth() > 0) && (specLhs.cardinality() >= this.posCover.getMaxDepth()))
                    continue;

                for (int attr = numAttributes - 1; attr >= 0; attr--) { // TODO: Is iterating backwards a good or bad idea?
                    if (!lhs.get(attr) && (attr != rhs)) {
                        specLhs.set(attr);

                        if (!this.posCover.containsFdOrGeneralization(specLhs, rhs)) {
                            this.posCover.addFunctionalDependency(specLhs, rhs);
                            newFDs++;

                            // If dynamic memory management is enabled, frequently check the memory consumption and trim the positive cover if it does not fit anymore
                            this.memoryGuardian.memoryChanged(1);
                            this.memoryGuardian.match(this.negCover, this.posCover, nonFds);
                        }
                        specLhs.clear(attr);
                    }
                }
            }
        }

        return newFDs;
    }

}
