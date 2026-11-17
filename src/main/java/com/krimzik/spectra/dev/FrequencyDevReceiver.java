package com.krimzik.spectra.dev;

import com.krimzik.spectra.spectra;
import com.krimzik.spectra.api.FrequencyApi;
import com.krimzik.spectra.api.FrequencySpace;
import com.krimzik.spectra.api.TRx.FrequencyReceiver;
import com.krimzik.spectra.api.material.FrequencyMaterialAttenuation;
import com.krimzik.spectra.api.material.FrequencySableMaterialAttenuation;
import com.krimzik.spectra.api.registration.FrequencyRegistration;
import com.krimzik.spectra.math.FrequencyPropagation;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Locale;
import java.util.function.Supplier;

public final class FrequencyDevReceiver {
    private static final double RANGE = 256.0;

    private static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, spectra.MODID);
    private static final Supplier<TestBlock> BLOCK = BLOCKS.register("dev_receiver", () -> new TestBlock(BlockBehaviour.Properties.of().strength(1.0F)));
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(spectra.MODID);
    private static final Supplier<BlockItem> ITEM = ITEMS.register("dev_receiver", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, spectra.MODID);
    private static final Supplier<BlockEntityType<TestBlockEntity>> TYPE = BLOCK_ENTITIES.register("dev_receiver", () -> BlockEntityType.Builder.of(TestBlockEntity::new, BLOCK.get()).build(null));

    private FrequencyDevReceiver() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    private static final class TestBlock extends BaseEntityBlock {
        private static final MapCodec<TestBlock> CODEC = simpleCodec(TestBlock::new);

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

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof TestBlockEntity receiver) {
                receiver.inspect(serverPlayer, serverLevel);
            }

            return InteractionResult.SUCCESS;
        }
    }

    private static final class TestBlockEntity extends BlockEntity {
        private final FrequencyReceiver receiver = new ProbeReceiver(this);
        private FrequencyRegistration registration;

        private TestBlockEntity(BlockPos pos, BlockState state) {
            super(TYPE.get(), pos, state);
        }

        @Override
        public void onLoad() {
            super.onLoad();

            if (level instanceof ServerLevel) {
                closeRegistration();
                registration = FrequencyApi.registerBlockReceiver(this, receiver);
            }
        }

        @Override
        public void onChunkUnloaded() {
            closeRegistration();
        }

        @Override
        public void setRemoved() {
            closeRegistration();
            super.setRemoved();
        }

        private void closeRegistration() {
            if (registration != null) {
                registration.close();
                registration = null;
            }
        }

        private void inspect(ServerPlayer player, ServerLevel level) {
            if (registration == null) {
                player.sendSystemMessage(Component.literal("[Spectra RX] Receiver is not registered."));
                return;
            }

            int[] matches = {0};

            FrequencyApi.get(level).forEachReceivedInRange(
                    receiver,
                    RANGE,
                    (emitter, signal, distanceSquared, amplitude, travelTime) -> {
                        if (!FrequencyDevTransmitter.isDevEmitter(emitter)) {
                            return;
                        }

                        matches[0]++;
                        if (matches[0] != 1) {
                            return;
                        }

                        Vec3 start = FrequencySpace.worldPosition(level, emitter.position());
                        Vec3 end = FrequencySpace.worldPosition(level, receiver.position());

                        double distance = Math.sqrt(distanceSquared);
                        double baseline = FrequencyPropagation.amplitudeAtDistance(signal.amplitude(), distance);
                        double world = FrequencyMaterialAttenuation.factor(level, start, end);
                        double sable = FrequencySableMaterialAttenuation.factor(level, start, end);
                        double combined = world * sable;
                        double expected = baseline * combined;
                        boolean consistent = Math.abs(amplitude - expected) <= 1.0e-12 * Math.max(1.0, Math.abs(expected));

                        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[Spectra RX] Distance=%.3f m Baseline=%.9g Received=%.9g", distance, baseline, amplitude)));
                        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[Spectra RX] World=%.9f Sable=%.9f Combined=%.9f", world, sable, combined)));
                        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[Spectra RX] Expected=%.9g EngineMatch=%s", expected, consistent)));
                    }
            );

            if (matches[0] == 0) {
                player.sendSystemMessage(Component.literal("[Spectra RX] No dev transmitter found within 256 meters."));
            } else if (matches[0] > 1) {
                player.sendSystemMessage(Component.literal("[Spectra RX] Found " + matches[0] + " dev transmitters. Showing the first."));
            }
        }
    }

    private record ProbeReceiver(TestBlockEntity owner) implements FrequencyReceiver {
        @Override
        public Vec3 position() {
            return Vec3.atCenterOf(owner.getBlockPos());
        }

        @Override
        public double tunedFrequencyHz() {
            return FrequencyDevTransmitter.testFrequencyHz();
        }

        @Override
        public double toleranceHz() {
            return 0.0;
        }
    }
}