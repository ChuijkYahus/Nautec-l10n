package com.breakinblocks.nautec.gametest.suite;

import com.breakinblocks.nautec.NTRegistries;
import com.breakinblocks.nautec.Nautec;
import com.breakinblocks.nautec.api.bacteria.Bacteria;
import com.breakinblocks.nautec.NTConfig;
import com.breakinblocks.nautec.content.blockentities.MixerBlockEntity;
import com.breakinblocks.nautec.content.blockentities.generators.CombustionDynamoBlockEntity;
import com.breakinblocks.nautec.data.maps.CombustionFuel;
import com.breakinblocks.nautec.content.blocks.generators.CombustionDynamoBlock;
import com.breakinblocks.nautec.registries.NTBacterias;
import com.breakinblocks.nautec.registries.NTBlocks;
import com.breakinblocks.nautec.registries.NTFluids;
import com.breakinblocks.nautec.registries.NTItems;
import com.breakinblocks.nautec.tags.NTTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public final class CombustionDynamoTests {
    private static final BlockPos POS = new BlockPos(4, 2, 4);

    private CombustionDynamoTests() {
    }

    private static CombustionDynamoBlockEntity dynamo(GameTestHelper helper) {
        helper.setBlock(POS, NTBlocks.COMBUSTION_DYNAMO.get());
        return helper.getBlockEntity(POS, CombustionDynamoBlockEntity.class);
    }

    private static void fuel(CombustionDynamoBlockEntity dynamo, int oil, int water) {
        dynamo.getFluidTank().fill(new FluidStack(NTFluids.OIL.getStillFluid(), oil));
        dynamo.getSecondaryFluidTank().fill(new FluidStack(Fluids.WATER, water));
    }

    private static void run(CombustionDynamoBlockEntity dynamo, int ticks) {
        for (int i = 0; i < ticks; i++) {
            dynamo.commonTick();
        }
    }

    private static MixerBlockEntity mixer(GameTestHelper helper, FluidStack fluid, ItemStack... items) {
        helper.setBlock(POS, NTBlocks.MIXER.get());
        MixerBlockEntity mixer = helper.getBlockEntity(POS, MixerBlockEntity.class);
        for (int i = 0; i < items.length; i++) {
            mixer.getItemStackHandler().setStackInSlot(i, items[i]);
        }
        helper.assertValueEqual(mixer.getFluidTank().fill(fluid), fluid.getAmount(), "input fluid filled");
        return mixer;
    }

    private static void mix(MixerBlockEntity mixer, int ticks) {
        for (int i = 0; i < ticks; i++) {
            mixer.setPowerPerSide(Direction.NORTH, NTConfig.mixerPower);
            mixer.commonTick();
        }
    }

    private static int insert(ResourceHandler<FluidResource> handler, FluidStack stack) {
        try (Transaction tx = Transaction.openRoot()) {
            int inserted = handler.insert(FluidResource.of(stack), stack.getAmount(), tx);
            tx.commit();
            return inserted;
        }
    }

    public static void register(NTTestRegistrar r) {
        r.add("combustion_dynamo/burns_oil_and_water_into_fe", 20, helper -> {
            CombustionDynamoBlockEntity dynamo = dynamo(helper);
            fuel(dynamo, 1000, 1000);
            run(dynamo, 100);
            helper.assertValueEqual(dynamo.getStatus(), CombustionDynamoBlockEntity.Status.RUNNING, "status");
            helper.assertValueEqual(dynamo.getRate(), 320, "base output per tick");
            helper.assertValueEqual(dynamo.getEnergyStorage().getAmountAsInt(), 32_000, "FE after 100 ticks");
            helper.assertValueEqual(dynamo.getFluidTank().getFluidAmount(), 990, "oil left: 1 mB per 10 ticks");
            helper.assertValueEqual(dynamo.getSecondaryFluidTank().getFluidAmount(), 900, "water left: 1 mB per tick");
            helper.assertTrue(helper.getBlockState(POS).getValue(CombustionDynamoBlock.LIT), "it lights up while burning");
            helper.succeed();
        });

        r.add("combustion_dynamo/redstone_boosts_output_and_fuel_use", 20, helper -> {
            CombustionDynamoBlockEntity dynamo = dynamo(helper);
            fuel(dynamo, 1000, 1000);
            dynamo.getItemStackHandler().setStackInSlot(CombustionDynamoBlockEntity.ADDITIVE_SLOT, new ItemStack(Items.REDSTONE, 2));
            run(dynamo, 100);
            helper.assertValueEqual(dynamo.getRate(), 480, "boosted output per tick");
            helper.assertValueEqual(dynamo.getEnergyStorage().getAmountAsInt(), 48_000, "FE after 100 boosted ticks");
            helper.assertValueEqual(dynamo.getFluidTank().getFluidAmount(), 989, "oil left: 10% more than unboosted");
            helper.assertValueEqual(dynamo.getSecondaryFluidTank().getFluidAmount(), 890, "water left: 10% more than unboosted");
            helper.assertValueEqual(dynamo.getItemStackHandler().getStackInSlot(CombustionDynamoBlockEntity.ADDITIVE_SLOT).getCount(), 1,
                    "one redstone was used");
            helper.assertValueEqual(dynamo.getAdditiveLeft(), 1100, "boost ticks left");
            helper.succeed();
        });

        r.add("combustion_dynamo/one_redstone_lasts_a_minute_of_burning", 20, helper -> {
            CombustionDynamoBlockEntity dynamo = dynamo(helper);
            fuel(dynamo, 8000, 8000);
            dynamo.getItemStackHandler().setStackInSlot(CombustionDynamoBlockEntity.ADDITIVE_SLOT, new ItemStack(Items.REDSTONE, 1));
            for (int i = 0; i < 1200; i++) {
                dynamo.commonTick();
                dynamo.getEnergyStorage().set(0);
            }
            helper.assertValueEqual(dynamo.getAdditiveLeft(), 0, "the boost runs out after 1200 burning ticks");
            helper.assertTrue(dynamo.getItemStackHandler().getStackInSlot(CombustionDynamoBlockEntity.ADDITIVE_SLOT).isEmpty(), "the redstone is used up");
            dynamo.commonTick();
            helper.assertValueEqual(dynamo.getRate(), 320, "back to base output");
            helper.succeed();
        });

        r.add("combustion_dynamo/tanks_only_take_their_own_fluid", 20, helper -> {
            CombustionDynamoBlockEntity dynamo = dynamo(helper);
            helper.assertValueEqual(dynamo.getFluidTank().fill(new FluidStack(Fluids.WATER, 1000)), 0, "water refused by the oil tank");
            helper.assertValueEqual(dynamo.getSecondaryFluidTank().fill(new FluidStack(NTFluids.OIL.getStillFluid(), 1000)), 0,
                    "oil refused by the water tank");
            ResourceHandler<FluidResource> side = dynamo.getFluidHandlerOnSide(Direction.UP);
            helper.assertValueEqual(insert(side, new FluidStack(Fluids.WATER, 1000)), 1000, "water piped in from a side");
            helper.assertValueEqual(insert(side, new FluidStack(NTFluids.OIL.getStillFluid(), 1000)), 1000, "oil piped in from a side");
            helper.assertValueEqual(insert(side, new FluidStack(Fluids.LAVA, 1000)), 0, "lava refused");
            helper.assertValueEqual(dynamo.getFluidTank().getFluidAmount(), 1000, "oil landed in the oil tank");
            helper.assertValueEqual(dynamo.getSecondaryFluidTank().getFluidAmount(), 1000, "water landed in the water tank");
            helper.succeed();
        });

        r.add("combustion_dynamo/needs_both_oil_and_water", 20, helper -> {
            CombustionDynamoBlockEntity dynamo = dynamo(helper);
            fuel(dynamo, 1000, 0);
            run(dynamo, 20);
            helper.assertValueEqual(dynamo.getStatus(), CombustionDynamoBlockEntity.Status.NO_WATER, "status without water");
            helper.assertValueEqual(dynamo.getEnergyStorage().getAmountAsInt(), 0, "no FE without water");
            helper.assertValueEqual(dynamo.getFluidTank().getFluidAmount(), 1000, "no oil burned without water");
            dynamo.getFluidTank().drain(1000);
            fuel(dynamo, 0, 1000);
            run(dynamo, 20);
            helper.assertValueEqual(dynamo.getStatus(), CombustionDynamoBlockEntity.Status.NO_OIL, "status without oil");
            helper.assertValueEqual(dynamo.getSecondaryFluidTank().getFluidAmount(), 1000, "no water used without oil");
            helper.succeed();
        });

        r.add("combustion_dynamo/oil_chain_is_complete", 20, helper -> {
            helper.assertTrue(NTFluids.OIL.getStillFluid().defaultFluidState().is(NTTags.Fluids.OIL), "NauTec oil is in c:oil");
            var recipes = helper.getLevel().recipeAccess();
            helper.assertTrue(NTFluids.BIODIESEL.getStillFluid().defaultFluidState().is(NTTags.Fluids.BIODIESEL), "NauTec biodiesel is in c:biodiesel");
            for (String id : new String[]{"kelp_slurry_mixing", "oil_from_algal_lipid_mixing", "combustion_additive/redstone",
                    "biodiesel_mixing", "etching_acid_from_glycerin_mixing"}) {
                helper.assertTrue(recipes.byKey(ResourceKey.create(Registries.RECIPE, Nautec.rl(id))).isPresent(), "recipe " + id);
            }
            Bacteria lipophiles = helper.getLevel().registryAccess().lookupOrThrow(NTRegistries.BACTERIA_KEY).getValueOrThrow(NTBacterias.LIPOPHILES);
            helper.assertTrue(lipophiles.resource().resolve() == NTItems.ALGAL_LIPID.get(), "Lipophiles produce Algal Lipid");
            helper.succeed();
        });

        r.add("combustion_dynamo/burns_biodiesel_hotter_and_longer", 20, helper -> {
            CombustionDynamoBlockEntity dynamo = dynamo(helper);
            dynamo.getFluidTank().fill(new FluidStack(NTFluids.BIODIESEL.getStillFluid(), 1000));
            dynamo.getSecondaryFluidTank().fill(new FluidStack(Fluids.WATER, 1000));
            run(dynamo, 120);
            helper.assertValueEqual(dynamo.getStatus(), CombustionDynamoBlockEntity.Status.RUNNING, "status");
            helper.assertValueEqual(dynamo.getRate(), 640, "biodiesel output per tick");
            helper.assertValueEqual(dynamo.getEnergyStorage().getAmountAsInt(), 76_800, "FE after 120 ticks");
            helper.assertValueEqual(dynamo.getFluidTank().getFluidAmount(), 990, "biodiesel left: 1 mB per 12 ticks");
            helper.assertValueEqual(dynamo.getSecondaryFluidTank().getFluidAmount(), 880, "water left: 1 mB per tick");
            helper.succeed();
        });

        r.add("combustion_dynamo/fuel_values_come_from_the_data_map", 20, helper -> {
            helper.assertValueEqual(CombustionDynamoBlockEntity.fuelOf(new FluidStack(NTFluids.OIL.getStillFluid(), 1)), new CombustionFuel(320, 10), "oil entry");
            helper.assertValueEqual(CombustionDynamoBlockEntity.fuelOf(new FluidStack(NTFluids.BIODIESEL.getStillFluid(), 1)), new CombustionFuel(640, 12), "biodiesel entry");
            helper.assertTrue(CombustionDynamoBlockEntity.fuelOf(new FluidStack(Fluids.LAVA, 1)) == null, "lava has no entry");
            helper.assertTrue(CombustionDynamoBlockEntity.fuelOf(FluidStack.EMPTY) == null, "an empty tank has no fuel");
            var groups = CombustionDynamoBlockEntity.fuelGroups();
            helper.assertTrue(groups.stream().anyMatch(group -> group.fluids().contains(NTFluids.OIL.getStillFluid())), "JEI lists oil");
            helper.assertTrue(groups.stream().anyMatch(group -> group.fluids().contains(NTFluids.BIODIESEL.getStillFluid())), "JEI lists biodiesel");
            helper.assertTrue(groups.stream().noneMatch(group -> group.fluids().contains(NTFluids.OIL.getFlowingFluid())), "flowing fluids are not listed");
            CombustionDynamoBlockEntity dynamo = dynamo(helper);
            helper.assertValueEqual(dynamo.getFluidTank().fill(new FluidStack(NTFluids.BIODIESEL.getStillFluid(), 1000)), 1000, "biodiesel accepted");
            helper.assertValueEqual(dynamo.getFluidTank().fill(new FluidStack(NTFluids.OIL.getStillFluid(), 1000)), 0, "oil refused while biodiesel is in the tank");
            helper.succeed();
        });

        r.add("combustion_dynamo/mixer_refines_oil_into_biodiesel_and_glycerin", 20, helper -> {
            MixerBlockEntity mixer = mixer(helper, new FluidStack(NTFluids.OIL.getStillFluid(), 1000), new ItemStack(NTItems.SALT.get()));
            mix(mixer, 400);
            FluidStack out = mixer.getSecondaryFluidTank().getFluid();
            helper.assertTrue(out.is(NTFluids.BIODIESEL.getStillFluid()), "the output tank holds biodiesel, not " + out.getFluid());
            helper.assertValueEqual(out.getAmount(), 750, "biodiesel from 1,000 mB of oil");
            helper.assertValueEqual(mixer.getInputFluidAmount(), 0, "the oil is used up");
            ItemStack glycerin = mixer.getItemStackHandler().getStackInSlot(MixerBlockEntity.OUTPUT_SLOT);
            helper.assertTrue(glycerin.is(NTItems.CRUDE_GLYCERIN.get()) && glycerin.getCount() == 1, "one Crude Glycerin, got " + glycerin);
            helper.succeed();
        });

        r.add("combustion_dynamo/glycerin_mixes_into_etching_acid", 20, helper -> {
            MixerBlockEntity mixer = mixer(helper, new FluidStack(NTFluids.SALT_WATER.getStillFluid(), 1000),
                    new ItemStack(NTItems.CRUDE_GLYCERIN.get(), 2), new ItemStack(Items.BONE_MEAL));
            mix(mixer, 300);
            FluidStack out = mixer.getSecondaryFluidTank().getFluid();
            helper.assertTrue(out.is(NTFluids.ETCHING_ACID.getStillFluid()), "the output tank holds etching acid, not " + out.getFluid());
            helper.assertValueEqual(out.getAmount(), 1000, "etching acid made");
            helper.assertTrue(mixer.getItemStackHandler().getStackInSlot(0).isEmpty(), "both glycerin used");
            helper.succeed();
        });
    }
}
