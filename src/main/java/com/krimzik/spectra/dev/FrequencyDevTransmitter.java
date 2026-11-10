package com.krimzik.spectra.dev;

import com.krimzik.spectra.spectra;
import com.krimzik.spectra.api.FrequencyApi;
import com.krimzik.spectra.api.FrequencyEngine;
import com.krimzik.spectra.api.FrequencySignal;
import com.krimzik.spectra.api.TRx.FrequencyEmitter;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.registration.FrequencyRegistration;

import com.mojang.serialization.MapCodec;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class FrequencyDevTransmitter {
    private static final FrequencySignal SIGNAL = new FrequencySignal(915_000_127.0, 1.0, 0.0, 299_792_458.0);
    private static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, spectra.MODID);
    private static final Supplier<TestBlock> BLOCK = BLOCKS.register("dev_transmitter", () -> new TestBlock(BlockBehaviour.Properties.of().strength(1.0F)));
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, spectra.MODID);
    private static final Supplier<BlockEntityType<TestBlockEntity>> TYPE = BLOCK_ENTITIES.register("dev_transmitter", () -> BlockEntityType.Builder.of(TestBlockEntity::new, BLOCK.get()).build(null));

    private static int loads;
    private static int removals;
    private static int chunkUnloads;
    private static int registrations;
    private static int ownerCloses;

    private FrequencyDevTransmitter() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);

        NeoForge.EVENT_BUS.addListener(FrequencyDevTransmitter::onCommands);
    }

    private static final class TestBlock extends BaseEntityBlock {
        public static final MapCodec<TestBlock> CODEC = simpleCodec(TestBlock::new);

        private TestBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new TestBlockEntity(pos, state);
        }

        @Override
        protected RenderShape getRenderShape(BlockState state) {
            return RenderShape.MODEL;
        }
    }

    private static final class TestBlockEntity extends BlockEntity {
        private final FrequencyEmitter emitter = new ProbeEmitter(this);
        private FrequencyRegistration registration;

        private TestBlockEntity(BlockPos pos, BlockState state) {
            super(TYPE.get(), pos, state);
        }

        @Override
        public void onLoad() {
            super.onLoad();

            if (level instanceof ServerLevel) {
                loads++;

                closeRegistration();

                registration = FrequencyApi.registerBlockEmitter(this, emitter);
                registrations++;
            }
        }

        @Override
        public void onChunkUnloaded() {
            if (level instanceof ServerLevel) {
                chunkUnloads++;
            }

            closeRegistration();
        }

        @Override
        public void setRemoved() {
            if (level instanceof ServerLevel) {
                removals++;
            }

            closeRegistration();
            super.setRemoved();
        }

        private void closeRegistration() {
            if (registration != null) {
                registration.close();
                registration = null;
                ownerCloses++;
            }
        }
    }

    private static final class ProbeEmitter implements FrequencyEmitter {
        private final TestBlockEntity owner;

        private ProbeEmitter(TestBlockEntity owner) {
            this.owner = owner;
        }

        @Override
        public Vec3 position() {
            return Vec3.atCenterOf(owner.getBlockPos());
        }

        @Override
        public FrequencySignal signal() {
            return SIGNAL;
        }
    }

    private static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("freqtest")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("block")
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource().getPlayerOrException()))))
        );
    }

    private static int status(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        FrequencyEngine engine = FrequencyApi.get(level);

        int[] indexed = {0};
        int[] stale = {0};

        engine.forEachMatching(new FrequencyReceiver() {
            @Override
            public Vec3 position() {
                return Vec3.ZERO;
            }

            @Override
            public double tunedFrequencyHz() {
                return SIGNAL.frequencyHz();
            }

            @Override
            public double toleranceHz() {
                return 0.0;
            }
        }, (emitter, signal) -> {
            if (emitter instanceof ProbeEmitter probe) {
                indexed[0]++;

                if (probe.owner.isRemoved()) {
                    stale[0]++;
                }
            }
        });

        player.sendSystemMessage(Component.literal("[FreqTestBlock] indexed=" + indexed[0] + " stale=" + stale[0] + " engineTotal=" + engine.emitterCount()));
        player.sendSystemMessage(Component.literal("[FreqTestBlock] loads=" + loads + " registrations=" + registrations + " ownerCloses=" + ownerCloses + " removals=" + removals + " chunkUnloads=" + chunkUnloads));

        return stale[0] == 0 && indexed[0] <= 1 ? 1 : 0;
    }
}
