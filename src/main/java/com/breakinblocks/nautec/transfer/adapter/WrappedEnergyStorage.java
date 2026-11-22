package com.breakinblocks.nautec.transfer.adapter;

import com.breakinblocks.nautec.transfer.TransferPreconditions;
import com.breakinblocks.nautec.transfer.energy.EnergyHandler;
import com.breakinblocks.nautec.transfer.transaction.SnapshotJournal;
import com.breakinblocks.nautec.transfer.transaction.TransactionContext;
import net.neoforged.neoforge.energy.IEnergyStorage;

public final class WrappedEnergyStorage implements EnergyHandler {
    private final IEnergyStorage storage;
    private final Journal journal = new Journal();

    public WrappedEnergyStorage(IEnergyStorage storage) {
        this.storage = storage;
    }

    public IEnergyStorage storage() {
        return storage;
    }

    @Override
    public long getAmountAsLong() {
        return Math.max(0, storage.getEnergyStored() - journal.taken);
    }

    @Override
    public long getCapacityAsLong() {
        return storage.getMaxEnergyStored();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (amount == 0 || !storage.canReceive() || storage.receiveEnergy(amount, true) <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        int inserted = storage.receiveEnergy(amount, false);
        journal.inserted += inserted;
        return inserted;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (amount == 0 || !storage.canExtract()) {
            return 0;
        }
        long simulated = storage.extractEnergy((int) Math.min(Integer.MAX_VALUE, journal.taken + amount), true);
        int extracted = (int) (simulated - journal.taken);
        if (extracted <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        journal.taken += extracted;
        return extracted;
    }

    private final class Journal extends SnapshotJournal<long[]> {
        private long inserted;
        private long taken;

        @Override
        protected long[] createSnapshot() {
            return new long[] {inserted, taken};
        }

        @Override
        protected void revertToSnapshot(long[] snapshot) {
            long delta = inserted - snapshot[0];
            if (delta > 0) {
                storage.extractEnergy((int) Math.min(Integer.MAX_VALUE, delta), false);
            }
            inserted = snapshot[0];
            taken = snapshot[1];
        }

        @Override
        protected void onRootCommit(long[] originalState) {
            long pending = taken;
            inserted = 0;
            taken = 0;
            while (pending > 0) {
                int extracted = storage.extractEnergy((int) Math.min(Integer.MAX_VALUE, pending), false);
                if (extracted <= 0) {
                    break;
                }
                pending -= extracted;
            }
        }
    }
}
