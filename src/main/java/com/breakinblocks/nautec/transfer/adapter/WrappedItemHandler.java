package com.breakinblocks.nautec.transfer.adapter;

import com.breakinblocks.nautec.transfer.ResourceHandler;
import com.breakinblocks.nautec.transfer.TransferPreconditions;
import com.breakinblocks.nautec.transfer.item.ItemResource;
import com.breakinblocks.nautec.transfer.transaction.SnapshotJournal;
import com.breakinblocks.nautec.transfer.transaction.TransactionContext;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;

public final class WrappedItemHandler implements ResourceHandler<ItemResource> {
    private final IItemHandler handler;
    private final Journal journal = new Journal();

    public WrappedItemHandler(IItemHandler handler) {
        this.handler = handler;
    }

    public IItemHandler handler() {
        return handler;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public ItemResource getResource(int index) {
        ItemStack stack = handler.getStackInSlot(index);
        return stack.getCount() <= journal.taken(index) ? ItemResource.EMPTY : ItemResource.of(stack);
    }

    @Override
    public long getAmountAsLong(int index) {
        return Math.max(0, handler.getStackInSlot(index).getCount() - journal.taken(index));
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        int limit = handler.getSlotLimit(index);
        return resource.isEmpty() ? limit : Math.min(limit, resource.getMaxStackSize());
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return !resource.isEmpty() && handler.isItemValid(index, resource.toStack());
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0) {
            return 0;
        }
        ItemStack remainder = handler.insertItem(index, resource.toStack(amount), true);
        int inserted = amount - remainder.getCount();
        if (inserted <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        ItemStack previous = handler.getStackInSlot(index).copy();
        ItemStack actual = handler.insertItem(index, resource.toStack(inserted), false);
        int done = inserted - actual.getCount();
        if (done > 0) {
            journal.record(index, previous, resource, done);
        }
        return done;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0) {
            return 0;
        }
        ItemStack stack = handler.getStackInSlot(index);
        int taken = journal.taken(index);
        if (stack.getCount() <= taken || !resource.matches(stack)) {
            return 0;
        }
        ItemStack simulated = handler.extractItem(index, (int) Math.min(Integer.MAX_VALUE, (long) taken + amount), true);
        if (simulated.isEmpty() || !resource.matches(simulated)) {
            return 0;
        }
        int extracted = simulated.getCount() - taken;
        if (extracted <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        journal.take(index, extracted);
        return extracted;
    }

    private final class Journal extends SnapshotJournal<Journal.State> {
        private final IntArrayList slots = new IntArrayList();
        private final List<ItemStack> previousStacks = new ArrayList<>();
        private final List<ItemResource> resources = new ArrayList<>();
        private final IntArrayList amounts = new IntArrayList();
        private Int2IntOpenHashMap taken = new Int2IntOpenHashMap();

        private record State(int inserts, Int2IntOpenHashMap taken) {
        }

        int taken(int slot) {
            return taken.get(slot);
        }

        void take(int slot, int amount) {
            taken.addTo(slot, amount);
        }

        void record(int slot, ItemStack previous, ItemResource resource, int amount) {
            slots.add(slot);
            previousStacks.add(previous);
            resources.add(resource);
            amounts.add(amount);
        }

        @Override
        protected State createSnapshot() {
            return new State(slots.size(), new Int2IntOpenHashMap(taken));
        }

        @Override
        protected void revertToSnapshot(State snapshot) {
            for (int i = slots.size() - 1; i >= snapshot.inserts(); i--) {
                int slot = slots.getInt(i);
                if (handler instanceof IItemHandlerModifiable modifiable) {
                    modifiable.setStackInSlot(slot, previousStacks.get(i));
                } else {
                    handler.extractItem(slot, amounts.getInt(i), false);
                }
            }
            truncate(snapshot.inserts());
            taken = snapshot.taken();
        }

        @Override
        protected void onRootCommit(State originalState) {
            Int2IntOpenHashMap pending = taken;
            taken = new Int2IntOpenHashMap();
            truncate(0);
            for (Int2IntMap.Entry entry : pending.int2IntEntrySet()) {
                int slot = entry.getIntKey();
                int remaining = entry.getIntValue();
                while (remaining > 0) {
                    ItemStack extracted = handler.extractItem(slot, remaining, false);
                    if (extracted.isEmpty()) {
                        break;
                    }
                    remaining -= extracted.getCount();
                }
            }
        }

        private void truncate(int size) {
            slots.size(size);
            amounts.size(size);
            while (resources.size() > size) {
                int last = resources.size() - 1;
                resources.remove(last);
                previousStacks.remove(last);
            }
        }
    }
}
