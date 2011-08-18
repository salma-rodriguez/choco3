/**
 *  Copyright (c) 1999-2011, Ecole des Mines de Nantes
 *  All rights reserved.
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above copyright
 *        notice, this list of conditions and the following disclaimer in the
 *        documentation and/or other materials provided with the distribution.
 *      * Neither the name of the Ecole des Mines de Nantes nor the
 *        names of its contributors may be used to endorse or promote products
 *        derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS ``AS IS'' AND ANY
 *  EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 *  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package solver.variables.fast;

import choco.kernel.memory.IEnvironment;
import choco.kernel.memory.IStateBitSet;
import choco.kernel.memory.IStateInt;
import choco.kernel.memory.structure.OneWordS32BitSet;
import choco.kernel.memory.structure.OneWordS64BitSet;
import solver.ICause;
import solver.Solver;
import solver.constraints.propagators.Propagator;
import solver.exception.ContradictionException;
import solver.explanations.Explanation;
import solver.search.strategy.enumerations.values.heuristics.HeuristicVal;
import solver.variables.AbstractVariable;
import solver.variables.EventType;
import solver.variables.IntVar;
import solver.variables.Variable;
import solver.variables.domain.delta.Delta;
import solver.variables.domain.delta.IntDelta;
import solver.variables.domain.delta.NoDelta;

import java.util.BitSet;

/**
 * <br/>
 *
 * @author Charles Prud'homme
 * @since 18 nov. 2010
 */
public final class BitsetIntVarImpl extends AbstractVariable implements IntVar {

    private static final long serialVersionUID = 1L;

    protected boolean reactOnRemoval = false;

    //  Bitset of available values -- includes offset
    private final IStateBitSet VALUES;
    // Lower bound of the current domain -- includes offset
    private final IStateInt LB;
    // Upper bound of the current domain -- includes offset
    private final IStateInt UB;
    private final IStateInt SIZE;
    //offset of the lower bound and the first value in the domain
    private final int OFFSET;

    private IntDelta delta = NoDelta.singleton;

    protected HeuristicVal heuristicVal;

    //////////////////////////////////////////////////////////////////////////////////////

    public BitsetIntVarImpl(String name, int[] sortedValues, Solver solver) {
        super(name, solver);
        IEnvironment env = solver.getEnvironment();
        OFFSET = sortedValues[0];
        int capacity = sortedValues[sortedValues.length - 1] - OFFSET + 1;
        if (capacity < 32) {
            this.VALUES = new OneWordS32BitSet(env, capacity);
        } else if (capacity < 64) {
            this.VALUES = new OneWordS64BitSet(env, capacity);
        } else {
            this.VALUES = env.makeBitSet(capacity);
        }
        for (int i = 0; i < sortedValues.length; i++) {
            this.VALUES.set(sortedValues[i] - OFFSET, true);
        }
        this.LB = env.makeInt(0);
        this.UB = env.makeInt(capacity - 1);
        this.SIZE = env.makeInt(sortedValues.length);
    }

    public BitsetIntVarImpl(String name, int min, int max, Solver solver) {
        super(name, solver);
        IEnvironment env = solver.getEnvironment();
        this.OFFSET = min;
        int capacity = max - min + 1;
        if (capacity < 32) {
            this.VALUES = new OneWordS32BitSet(env, capacity);
        } else if (capacity < 64) {
            this.VALUES = new OneWordS64BitSet(env, capacity);
        } else {
            this.VALUES = env.makeBitSet(capacity);
        }
        for (int i = 0; i <= max - min; i++) {
            this.VALUES.set(i, true);
        }
        this.LB = env.makeInt(0);
        this.UB = env.makeInt(max - min);
        this.SIZE = env.makeInt(capacity);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void setHeuristicVal(HeuristicVal heuristicVal) {
        this.heuristicVal = heuristicVal;
    }

    @Override
    public HeuristicVal getHeuristicVal() {
        return heuristicVal;
    }

    /**
     * Removes <code>value</code>from the domain of <code>this</code>. The instruction comes from <code>propagator</code>.
     * <ul>
     * <li>If <code>value</code> is out of the domain, nothing is done and the return value is <code>false</code>,</li>
     * <li>if removing <code>value</code> leads to a dead-end (domain wipe-out),
     * a <code>ContradictionException</code> is thrown,</li>
     * <li>otherwise, if removing <code>value</code> from the domain can be done safely,
     * the event type is created (the original event can be promoted) and observers are notified
     * and the return value is <code>true</code></li>
     * </ul>
     *
     * @param value value to remove from the domain (int)
     * @param cause removal releaser
     * @return true if the value has been removed, false otherwise
     * @throws solver.exception.ContradictionException
     *          if the domain become empty due to this action
     */
    public boolean removeValue(int value, ICause cause) throws ContradictionException {
        // BEWARE: THIS CODE SHOULD NOT BE MOVED TO THE DOMAIN TO NOT DECREASE PERFORMANCES!
        boolean change = false;
        int inf = getLB();
        int sup = getUB();
        if (value == inf && value == sup) {
            solver.explainer.removeValue(this, value, cause);
            this.contradiction(cause, MSG_REMOVE);
        } else {
            if (inf <= value && value <= sup) {
                EventType e = EventType.REMOVE;

                int aValue = value - OFFSET;
                change = VALUES.get(aValue);
                this.VALUES.set(aValue, false);
                if (change) {
                    this.SIZE.add(-1);
                    if (reactOnRemoval) {
                        delta.add(aValue + OFFSET);
                    }
                }

                if (value == inf) {
                    LB.set(VALUES.nextSetBit(aValue));
                    e = EventType.INCLOW;
                    cause = (cause != null && cause.reactOnPromotion() ? null : cause);
                } else if (value == sup) {
                    UB.set(VALUES.prevSetBit(aValue));
                    e = EventType.DECUPP;
                    cause = (cause != null && cause.reactOnPromotion() ? null : cause);
                }
                if (change && !VALUES.isEmpty()) {
                    if (this.instantiated()) {
                        e = EventType.INSTANTIATE;
                        cause = (cause != null && cause.reactOnPromotion() ? null : cause);
                    }
                    this.notifyPropagators(e, cause);
                } else {
                    if (VALUES.isEmpty()) {
                        solver.explainer.removeValue(this, value, cause);
                        this.contradiction(cause, MSG_EMPTY);
                    }
                }
            }
        }
        if (change) {
            solver.explainer.removeValue(this, value, cause);
        }
        return change;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeInterval(int from, int to, ICause cause) throws ContradictionException {
        if (from <= getLB())
            return updateLowerBound(to + 1, cause);
        else if (getUB() <= to)
            return updateUpperBound(from - 1, cause);
        else if (hasEnumeratedDomain()) {     // TODO: really ugly .........
            boolean anyChange = false;
            for (int v = this.nextValue(from - 1); v <= to; v = nextValue(v)) {
                anyChange |= removeValue(v, cause);
            }
            return anyChange;
        } else {
            return false;
        }
    }

    /**
     * Instantiates the domain of <code>this</code> to <code>value</code>. The instruction comes from <code>propagator</code>.
     * <ul>
     * <li>If the domain of <code>this</code> is already instantiated to <code>value</code>,
     * nothing is done and the return value is <code>false</code>,</li>
     * <li>If the domain of <code>this</code> is already instantiated to another value,
     * then a <code>ContradictionException</code> is thrown,</li>
     * <li>Otherwise, the domain of <code>this</code> is restricted to <code>value</code> and the observers are notified
     * and the return value is <code>true</code>.</li>
     * </ul>
     *
     * @param value instantiation value (int)
     * @param cause instantiation releaser
     * @return true if the instantiation is done, false otherwise
     * @throws solver.exception.ContradictionException
     *          if the domain become empty due to this action
     */
    public boolean instantiateTo(int value, ICause cause) throws ContradictionException {
        // BEWARE: THIS CODE SHOULD NOT BE MOVED TO THE DOMAIN TO NOT DECREASE PERFORMANCES!
        if (this.instantiated()) {
            if (value != this.getValue()) {
                solver.explainer.instantiateTo(this, value, cause);
                this.contradiction(cause, MSG_INST);
            }
            return false;
        } else if (contains(value)) {
            EventType e = EventType.INSTANTIATE;

            int aValue = value - OFFSET;
            if (reactOnRemoval) {
                int i = VALUES.nextSetBit(this.LB.get());
                for (; i < aValue; i = VALUES.nextSetBit(i + 1)) {
                    delta.add(i + OFFSET);
                }
                i = VALUES.nextSetBit(aValue + 1);
                for (; i >= 0; i = VALUES.nextSetBit(i + 1)) {
                    delta.add(i + OFFSET);
                }
            }
            this.VALUES.clear();
            this.VALUES.set(aValue);
            this.LB.set(aValue);
            this.UB.set(aValue);
            this.SIZE.set(1);

            if (VALUES.isEmpty()) {
                solver.explainer.removeValue(this, value, cause);
                this.contradiction(cause, MSG_EMPTY);
            }
            this.notifyPropagators(e, cause);
            solver.explainer.instantiateTo(this, value, cause);
            return true;
        } else {
            solver.explainer.instantiateTo(this, value, cause);
            this.contradiction(cause, MSG_UNKNOWN);
            return false;
        }
    }

    /**
     * Updates the lower bound of the domain of <code>this</code> to <code>value</code>.
     * The instruction comes from <code>propagator</code>.
     * <ul>
     * <li>If <code>value</code> is smaller than the lower bound of the domain, nothing is done and the return value is <code>false</code>,</li>
     * <li>if updating the lower bound to <code>value</code> leads to a dead-end (domain wipe-out),
     * a <code>ContradictionException</code> is thrown,</li>
     * <li>otherwise, if updating the lower bound to <code>value</code> can be done safely,
     * the event type is created (the original event can be promoted) and observers are notified
     * and the return value is <code>true</code></li>
     * </ul>
     *
     * @param value new lower bound (included)
     * @param cause updating releaser
     * @return true if the lower bound has been updated, false otherwise
     * @throws solver.exception.ContradictionException
     *          if the domain become empty due to this action
     */
    public boolean updateLowerBound(int value, ICause cause) throws ContradictionException {
        boolean change;
        int old = this.getLB();
        if (old < value) {
            if (this.getUB() < value) {
                solver.explainer.updateLowerBound(this, old, value, cause);
                this.contradiction(cause, MSG_LOW);
            } else {
                EventType e = EventType.INCLOW;

                int aValue = value - OFFSET;
                if (reactOnRemoval) {
                    //BEWARE: this loop significantly decreases performances
                    for (int i = old - OFFSET; i < aValue; i = VALUES.nextSetBit(i + 1)) {
                        delta.add(i + OFFSET);
                    }
                }
                VALUES.clear(old - OFFSET, aValue);
                LB.set(VALUES.nextSetBit(aValue));
                int _size = SIZE.get();
                int card = VALUES.cardinality();
                SIZE.set(card);
                change = _size - card > 0;

                if (instantiated()) {
                    e = EventType.INSTANTIATE;
                    cause = (cause != null && cause.reactOnPromotion() ? null : cause);
                }
                assert (change);
                this.notifyPropagators(e, cause);

                solver.explainer.updateLowerBound(this, old, value, cause);
                return change;

            }
        }
        return false;
    }

    /**
     * Updates the upper bound of the domain of <code>this</code> to <code>value</code>.
     * The instruction comes from <code>propagator</code>.
     * <ul>
     * <li>If <code>value</code> is greater than the upper bound of the domain, nothing is done and the return value is <code>false</code>,</li>
     * <li>if updating the upper bound to <code>value</code> leads to a dead-end (domain wipe-out),
     * a <code>ContradictionException</code> is thrown,</li>
     * <li>otherwise, if updating the upper bound to <code>value</code> can be done safely,
     * the event type is created (the original event can be promoted) and observers are notified
     * and the return value is <code>true</code></li>
     * </ul>
     *
     * @param value new upper bound (included)
     * @param cause update releaser
     * @return true if the upper bound has been updated, false otherwise
     * @throws solver.exception.ContradictionException
     *          if the domain become empty due to this action
     */
    public boolean updateUpperBound(int value, ICause cause) throws ContradictionException {
        boolean change;
        int old = this.getUB();
        if (old > value) {
            if (this.getLB() > value) {
                solver.explainer.updateUpperBound(this, old, value, cause);
                this.contradiction(cause, MSG_UPP);
            } else {
                EventType e = EventType.DECUPP;
                int aValue = value - OFFSET;
                if (reactOnRemoval) {
                    //BEWARE: this loop significantly decreases performances
                    for (int i = old - OFFSET; i > aValue; i = VALUES.prevSetBit(i - 1)) {
                        delta.add(i + OFFSET);
                    }
                }
                VALUES.clear(aValue + 1, old - OFFSET + 1);
                UB.set(VALUES.prevSetBit(aValue));
                int _size = SIZE.get();
                int card = VALUES.cardinality();
                SIZE.set(card);
                change = _size - card > 0;

                if (card == 1) {
                    e = EventType.INSTANTIATE;
                    cause = (cause != null && cause.reactOnPromotion() ? null : cause);
                }
                assert (change);
                this.notifyPropagators(e, cause);
                solver.explainer.updateUpperBound(this, old, value, cause);
                return change;
            }
        }
        return false;
    }

    public boolean instantiated() {
        return SIZE.get() == 1;
    }

    @Override
    public boolean instantiatedTo(int value) {
        return instantiated() && contains(value);
    }

    public boolean contains(int aValue) {
        aValue -= OFFSET;
        return aValue >= 0 && this.VALUES.get(aValue);
    }

    /**
     * Retrieves the current value of the variable if instantiated, otherwier the lower bound.
     *
     * @return the current value (or lower bound if not yet instantiated).
     */
    public int getValue() {
        assert instantiated() : name + " not instantiated";
        return getLB();
    }

    /**
     * Retrieves the lower bound of the variable
     *
     * @return the lower bound
     */
    public int getLB() {
        return this.LB.get() + OFFSET;
    }

    /**
     * Retrieves the upper bound of the variable
     *
     * @return the upper bound
     */
    public int getUB() {
        return this.UB.get() + OFFSET;
    }

    public int getDomainSize() {
        return SIZE.get();
    }

    public int nextValue(int aValue) {
        aValue -= OFFSET;
        int lb = LB.get();
        if (aValue < 0 || aValue < lb) return lb + OFFSET;
        aValue = VALUES.nextSetBit(aValue + 1);
        if (aValue > -1) return aValue + OFFSET;
        return Integer.MAX_VALUE;
    }

    @Override
    public int previousValue(int aValue) {
        aValue -= OFFSET;
        int ub = UB.get();
        if (aValue > ub) return ub + OFFSET;
        aValue = VALUES.prevSetBit(aValue - 1);
        if (aValue > -1) return aValue + OFFSET;
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean hasEnumeratedDomain() {
        return true;
    }

    @Override
    public IntDelta getDelta() {
        return delta;
    }

    public String toString() {
        StringBuilder s = new StringBuilder(20);
        s.append(name).append(" = ");
        if (SIZE.get() == 1) {
            s.append(this.getLB());
        } else {
            s.append('{').append(getLB());
            int nb = 5;
            for (int i = nextValue(getLB()); i < Integer.MAX_VALUE && nb > 0; i = nextValue(i)) {
                s.append(',').append(i);
                nb--;
            }
            if (nb == 0) {
                s.append("...,").append(this.getUB());
            }
            s.append('}');
        }
        return s.toString();
    }

    ////////////////////////////////////////////////////////////////
    ///// methode liees au fait qu'une variable est observable /////
    ////////////////////////////////////////////////////////////////

    @Override
    public void addPropagator(Propagator observer, int idxInProp) {
        modificationEvents |= observer.getPropagationConditions(idxInProp);
        if (!reactOnRemoval && ((modificationEvents & EventType.REMOVE.mask) != 0)) {
            delta = new Delta();
            reactOnRemoval = true;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public Explanation explain() {
        Explanation expl = new Explanation(null, null);
        BitSet invdom = solver.explainer.getRemovedValues(this);
        int val = invdom.nextSetBit(0);
        while (val != -1) {
            expl.add(solver.explainer.explain(this, val));
            val = invdom.nextSetBit(val + 1);
        }
        return expl;
    }

    @Override
    public void contradiction(ICause cause, String message) throws ContradictionException {
        engine.fails(cause, this, message);
    }


    @Override
    public int getType() {
        return Variable.INTEGER;
    }
}