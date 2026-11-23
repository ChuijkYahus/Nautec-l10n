package com.breakinblocks.nautec.data.maps;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;

public record CombustionFuel(int output, int ticksPerMb) {
    public static final Codec<CombustionFuel> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ExtraCodecs.NON_NEGATIVE_INT.fieldOf("output").forGetter(CombustionFuel::output),
            ExtraCodecs.intRange(1, 1_000_000).fieldOf("ticks_per_mb").forGetter(CombustionFuel::ticksPerMb)
    ).apply(inst, CombustionFuel::new));
}
