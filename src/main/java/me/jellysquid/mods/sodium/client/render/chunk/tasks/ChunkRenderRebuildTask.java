package me.jellysquid.mods.sodium.client.render.chunk.tasks;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.compat.immersive.ImmersiveConnectionRenderer;
import me.jellysquid.mods.sodium.client.gl.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBufferSorter;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildResult;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkMeshData;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderBounds;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.client.render.pipeline.context.ChunkRenderCacheLocal;
import me.jellysquid.mods.sodium.client.util.task.CancellationSource;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.coderbot.iris.compat.sodium.impl.block_context.ChunkBuildBuffersExt;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraftforge.client.model.data.ModelData;

import java.util.EnumMap;
import java.util.Map;

/**
 * Rebuilds all the meshes of a chunk for each given render pass with non-occluded blocks. The result is then uploaded
 * to graphics memory on the main thread.
 *
 * This task takes a slice of the world from the thread it is created on. Since these slices require rather large
 * array allocations, they are pooled to ensure that the garbage collector doesn't become overloaded.
 */
public class ChunkRenderRebuildTask extends ChunkRenderBuildTask {
    private final RenderSection render;
    private final ChunkRenderContext renderContext;
    private final int frame;
    private final Map<BlockPos, ModelData> modelDataMap;

    private Vec3d camera;

    private final boolean translucencySorting;

    public ChunkRenderRebuildTask(RenderSection render, ChunkRenderContext renderContext, int frame) {
        this.render = render;
        this.renderContext = renderContext;
        this.frame = frame;
        this.camera = Vec3d.ZERO;
        this.translucencySorting = SodiumClientMod.options().performance.useTranslucentFaceSorting;

        this.modelDataMap = MinecraftClient.getInstance().world.getModelDataManager().getAt(new ChunkPos(ChunkSectionPos.getSectionCoord(this.render.getOriginX()), ChunkSectionPos.getSectionCoord(this.render.getOriginZ())));
    }
    
    private final Xoroshiro128PlusPlusRandom random = new Xoroshiro128PlusPlusRandom(42L);

    public ChunkRenderRebuildTask withCameraPosition(Vec3d camera) {
        this.camera = camera;
        return this;
    }

    @Override
    public ChunkBuildResult performBuild(ChunkBuildContext buildContext, CancellationSource cancellationSource) {
        // COMPATIBLITY NOTE: Oculus relies on the LVT of this method being unchanged, at least in 16.5
        ChunkRenderData.Builder renderData = new ChunkRenderData.Builder();
        ChunkOcclusionDataBuilder occluder = new ChunkOcclusionDataBuilder();
        ChunkRenderBounds.Builder bounds = new ChunkRenderBounds.Builder();

        ChunkBuildBuffers buffers = buildContext.buffers;
        buffers.init(renderData, this.render.getChunkId());

        ChunkRenderCacheLocal cache = buildContext.cache;
        cache.init(this.renderContext);

        WorldSlice slice = cache.getWorldSlice();

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        Map<BlockPos, ModelData> modelDataMap = this.modelDataMap;

        // Initialise with minX/minY/minZ so initial getBlockState crash context is correct
        BlockPos.Mutable blockPos = new BlockPos.Mutable(minX, minY, minZ);
        BlockPos.Mutable offset = new BlockPos.Mutable();

        try {
            for (int y = minY; y < maxY; y++) {
                if (cancellationSource.isCancelled()) {
                    return null;
                }

                for (int z = minZ; z < maxZ; z++) {
                    for (int x = minX; x < maxX; x++) {
                        BlockState blockState = slice.getBlockState(x, y, z);

                        if (blockState.isAir()) {
                            continue;
                        }

                        blockPos.set(x, y, z);
                        offset.set(x & 15, y & 15, z & 15);

                        boolean rendered = false;

                        if (blockState.getRenderType() == BlockRenderType.MODEL) {
                            BakedModel model = cache.getBlockModels()
                                    .getModel(blockState);
                            ModelData modelData = modelDataMap.getOrDefault(blockPos, ModelData.EMPTY);
                            random.setSeed(blockState.getRenderingSeed(blockPos));
                            for (RenderLayer layer : model.getRenderTypes(blockState, random, modelData)) {
                                if (SodiumClientMod.oculusLoaded && buildContext.buffers instanceof ChunkBuildBuffersExt) {
                                    ((ChunkBuildBuffersExt) buildContext.buffers).iris$setMaterialId(blockState, (short) -1);
                                }

                                long seed = blockState.getRenderingSeed(blockPos);

                                if (cache.getBlockRenderer().renderModel(cache.getLocalSlice(), blockState, blockPos, offset, model, buffers.get(layer), true, seed, modelData, layer, random)) {
                                    rendered = true;
                                }
                            }
                        }

                        FluidState fluidState = blockState.getFluidState();

                        if (!fluidState.isEmpty()) {
                            RenderLayer layer = RenderLayers.getFluidLayer(fluidState);

                            if (SodiumClientMod.oculusLoaded && buildContext.buffers instanceof ChunkBuildBuffersExt) {
                                // All fluids have a ShadersMod render type of 1, to match behavior of Minecraft 1.7 and earlier.
                                ((ChunkBuildBuffersExt) buildContext.buffers).iris$setMaterialId(fluidState.getBlockState(), (short) 1);
                            }

                            if (cache.getFluidRenderer().render(cache.getLocalSlice(), fluidState, blockPos, offset, buffers.get(layer))) {
                                rendered = true;
                            }
                        }

                        if (blockState.hasBlockEntity()) {
                            BlockEntity entity = slice.getBlockEntity(blockPos);

                            if (entity != null) {
                                BlockEntityRenderer<BlockEntity> renderer = MinecraftClient.getInstance().getBlockEntityRenderDispatcher().get(entity);

                                if (renderer != null) {
                                    renderData.addBlockEntity(entity, !renderer.rendersOutsideBoundingBox(entity));
                                    rendered = true;
                                }
                            }
                        }

                        if (blockState.isOpaqueFullCube(slice, blockPos)) {
                            occluder.markClosed(blockPos);
                        }

                        if (rendered) {
                            bounds.addBlock(x & 15, y & 15, z & 15);
                        }
                    }
                }
            }
        } catch (CrashException ex) {
            // Propagate existing crashes (add context)
            throw fillCrashInfo(ex.getReport(), slice, blockPos);
        } catch (Exception ex) {
            // Create a new crash report for other exceptions (e.g. thrown in getQuads)
            throw fillCrashInfo(CrashReport.create(ex, "Encountered exception while building chunk meshes"), slice, blockPos);
        }

        Map<BlockRenderPass, ChunkMeshData> meshes = new EnumMap<>(BlockRenderPass.class);
        
        if(SodiumClientMod.immersiveLoaded)
	        ImmersiveConnectionRenderer.renderConnectionsInSection(
	                buildContext.buffers, buildContext.cache.getWorldSlice(), render.getChunkPos()
	    );

        for (BlockRenderPass pass : BlockRenderPass.VALUES) {
            ChunkMeshData mesh = buffers.createMesh(pass);

            if (mesh != null) {
                if (this.translucencySorting && pass.isTranslucent())
                    ChunkBufferSorter.sort(ChunkBufferSorter.SortBuffer.wrap(mesh), (float)camera.x - minX, (float)camera.y - minY, (float)camera.z - minZ);

                meshes.put(pass, mesh);
            }
        }

        renderData.setOcclusionData(occluder.build());
        renderData.setBounds(bounds.build(this.render.getChunkPos()));

        return new ChunkBuildResult(this.render, renderData.build(), meshes, this.frame);
    }

    private CrashException fillCrashInfo(CrashReport report, WorldSlice slice, BlockPos pos) {
        CrashReportSection crashReportSection = report.addElement("Block being rendered", 1);

        BlockState state = null;
        try {
            state = slice.getBlockState(pos);
        } catch (Exception ignored) {}
        CrashReportSection.addBlockInfo(crashReportSection, slice, pos, state);

        crashReportSection.add("Chunk section", render);
        if (renderContext != null) {
            crashReportSection.add("Render context volume", renderContext.getVolume());
        }

        return new CrashException(report);
    }
    
    @Override
    public void releaseResources() {
        this.renderContext.releaseResources();
    }
}
