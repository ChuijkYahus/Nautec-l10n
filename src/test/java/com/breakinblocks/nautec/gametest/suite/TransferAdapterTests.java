package com.breakinblocks.nautec.gametest.suite;

import com.breakinblocks.nautec.transfer.ResourceHandler;
import com.breakinblocks.nautec.transfer.ResourceHandlerUtil;
import com.breakinblocks.nautec.transfer.adapter.WrappedEnergyStorage;
import com.breakinblocks.nautec.transfer.adapter.WrappedFluidHandler;
import com.breakinblocks.nautec.transfer.adapter.WrappedItemHandler;
import com.breakinblocks.nautec.transfer.energy.EnergyHandler;
import com.breakinblocks.nautec.transfer.energy.EnergyHandlerUtil;
import com.breakinblocks.nautec.transfer.fluid.FluidResource;
import com.breakinblocks.nautec.transfer.item.ItemResource;
import com.breakinblocks.nautec.transfer.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

public final class TransferAdapterTests {
    private TransferAdapterTests() {
    }

    public static void register(NTTestRegistrar r) {
        r.add("transfer/output_slot_keeps_items_after_a_trial_pull", 5, helper -> {
            OutputSlot source = new OutputSlot(new ItemStack(Items.RAW_IRON, 16));
            ItemStackHandler target = new ItemStackHandler(1);
            ResourceHandler<ItemResource> from = new WrappedItemHandler(source);
            try (Transaction root = Transaction.openRoot()) {
                try (Transaction trial = Transaction.open(root)) {
                    helper.assertValueEqual(8, from.extract(0, ItemResource.of(Items.RAW_IRON), 8, trial), "trial pull");
                }
                root.commit();
            }
            helper.assertValueEqual(16, source.getStackInSlot(0).getCount(), "raw iron left after a trial pull");
            int moved = ResourceHandlerUtil.move(from, new WrappedItemHandler(target), resource -> true, 4, null);
            helper.assertValueEqual(4, moved, "raw iron moved");
            helper.assertValueEqual(12, source.getStackInSlot(0).getCount(), "raw iron left in the output slot");
            helper.assertValueEqual(4, target.getStackInSlot(0).getCount(), "raw iron delivered");
            helper.succeed();
        });

        r.add("transfer/drain_only_tank_keeps_fluid_after_a_trial_pull", 5, helper -> {
            DrainOnlyTank source = new DrainOnlyTank(8000);
            source.setFluid(new FluidStack(Fluids.WATER, 4000));
            FluidTank target = new FluidTank(8000);
            ResourceHandler<FluidResource> from = new WrappedFluidHandler(source);
            FluidResource water = FluidResource.of(new FluidStack(Fluids.WATER, 1));
            try (Transaction root = Transaction.openRoot()) {
                try (Transaction trial = Transaction.open(root)) {
                    helper.assertValueEqual(1000, from.extract(water, 1000, trial), "trial pull");
                }
                root.commit();
            }
            helper.assertValueEqual(4000, source.getFluidAmount(), "water left after a trial pull");
            int moved = ResourceHandlerUtil.move(from, new WrappedFluidHandler(target), resource -> true, 1000, null);
            helper.assertValueEqual(1000, moved, "water moved");
            helper.assertValueEqual(3000, source.getFluidAmount(), "water left in the drain only tank");
            helper.assertValueEqual(1000, target.getFluidAmount(), "water delivered");
            helper.succeed();
        });

        r.add("transfer/generator_keeps_energy_after_a_trial_pull", 5, helper -> {
            EnergyStorage source = new EnergyStorage(10000, 0, 1000, 5000);
            EnergyStorage target = new EnergyStorage(10000);
            EnergyHandler from = new WrappedEnergyStorage(source);
            try (Transaction root = Transaction.openRoot()) {
                try (Transaction trial = Transaction.open(root)) {
                    helper.assertValueEqual(500, from.extract(500, trial), "trial pull");
                }
                root.commit();
            }
            helper.assertValueEqual(5000, source.getEnergyStored(), "energy left after a trial pull");
            int moved = EnergyHandlerUtil.move(from, new WrappedEnergyStorage(target), 500, null);
            helper.assertValueEqual(500, moved, "energy moved");
            helper.assertValueEqual(4500, source.getEnergyStored(), "energy left in the generator");
            helper.assertValueEqual(500, target.getEnergyStored(), "energy delivered");
            helper.succeed();
        });
    }

    private static final class OutputSlot implements IItemHandler {
        private ItemStack stack;

        private OutputSlot(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return stack;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack inserted, boolean simulate) {
            return inserted;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0 || stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int count = Math.min(amount, stack.getCount());
            ItemStack extracted = stack.copyWithCount(count);
            if (!simulate) {
                stack = count == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - count);
            }
            return extracted;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack candidate) {
            return false;
        }
    }

    private static final class DrainOnlyTank extends FluidTank {
        private DrainOnlyTank(int capacity) {
            super(capacity);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }
    }
}
