package com.breakinblocks.nautec.transfer.adapter;

import com.breakinblocks.nautec.transfer.ResourceHandler;
import com.breakinblocks.nautec.transfer.TransferPreconditions;
import com.breakinblocks.nautec.transfer.fluid.FluidResource;
import com.breakinblocks.nautec.transfer.transaction.SnapshotJournal;
import com.breakinblocks.nautec.transfer.transaction.TransactionContext;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;

public final class WrappedFluidHandler implements ResourceHandler<FluidResource> {
    private final IFluidHandler handler;
    private final Journal journal = new Journal();

    public WrappedFluidHandler(IFluidHandler handler) {
        this.handler = handler;
    }

    public IFluidHandler handler() {
        return handler;
    }

    @Override
    public int size() {
        return handler.getTanks();
    }

    @Override
    public FluidResource getResource(int index) {
        return getAmountAsLong(index) <= 0 ? FluidResource.EMPTY : FluidResource.of(handler.getFluidInTank(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        FluidStack stack = handler.getFluidInTank(index);
        if (stack.isEmpty()) {
            return 0;
        }
        FluidResource resource = FluidResource.of(stack);
        long pending = journal.drained(resource);
        for (int i = 0; i < index && pending > 0; i++) {
            FluidStack earlier = handler.getFluidInTank(i);
            if (!earlier.isEmpty() && resource.equals(FluidResource.of(earlier))) {
                pending -= Math.min(pending, earlier.getAmount());
            }
        }
        return Math.max(0, stack.getAmount() - pending);
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        return handler.getTankCapacity(index);
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        return !resource.isEmpty() && handler.isFluidValid(index, resource.toStack(1000));
    }

    @Override
    public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
        return insert(resource, amount, transaction);
    }

    @Override
    public int insert(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0) {
            return 0;
        }
        FluidStack stack = resource.toStack(amount);
        int simulated = handler.fill(stack, IFluidHandler.FluidAction.SIMULATE);
        if (simulated <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        int filled = handler.fill(resource.toStack(simulated), IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) {
            journal.record(resource, filled);
        }
        return filled;
    }

    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        return extract(resource, amount, transaction);
    }

    @Override
    public int extract(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0) {
            return 0;
        }
        int drained = journal.drained(resource);
        FluidStack simulated = handler.drain(resource.toStack((int) Math.min(Integer.MAX_VALUE, (long) drained + amount)),
                IFluidHandler.FluidAction.SIMULATE);
        if (simulated.isEmpty() || !resource.matches(simulated)) {
            return 0;
        }
        int extracted = simulated.getAmount() - drained;
        if (extracted <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        journal.drain(resource, extracted);
        return extracted;
    }

    private final class Journal extends SnapshotJournal<Journal.State> {
        private final List<FluidResource> resources = new ArrayList<>();
        private final List<Integer> amounts = new ArrayList<>();
        private Object2IntOpenHashMap<FluidResource> drained = new Object2IntOpenHashMap<>();

        private record State(int fills, Object2IntOpenHashMap<FluidResource> drained) {
        }

        int drained(FluidResource resource) {
            return drained.getInt(resource);
        }

        void drain(FluidResource resource, int amount) {
            drained.addTo(resource, amount);
        }

        void record(FluidResource resource, int amount) {
            resources.add(resource);
            amounts.add(amount);
        }

        @Override
        protected State createSnapshot() {
            return new State(resources.size(), new Object2IntOpenHashMap<>(drained));
        }

        @Override
        protected void revertToSnapshot(State snapshot) {
            for (int i = resources.size() - 1; i >= snapshot.fills(); i--) {
                handler.drain(resources.get(i).toStack(amounts.get(i)), IFluidHandler.FluidAction.EXECUTE);
            }
            truncate(snapshot.fills());
            drained = snapshot.drained();
        }

        @Override
        protected void onRootCommit(State originalState) {
            Object2IntOpenHashMap<FluidResource> pending = drained;
            drained = new Object2IntOpenHashMap<>();
            truncate(0);
            for (Object2IntMap.Entry<FluidResource> entry : pending.object2IntEntrySet()) {
                handler.drain(entry.getKey().toStack(entry.getIntValue()), IFluidHandler.FluidAction.EXECUTE);
            }
        }

        private void truncate(int size) {
            while (resources.size() > size) {
                int last = resources.size() - 1;
                resources.remove(last);
                amounts.remove(last);
            }
        }
    }
}
