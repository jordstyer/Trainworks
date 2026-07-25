package com.trainworks.track;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension persistence for {@link TrackGraph}. Chunk index and Bezier
 * LUTs are intentionally not stored here -- they're cheap to rebuild from
 * nodes/edges at load time (design/track-graph.md §5).
 */
public class TrackGraphSavedData extends SavedData {
    private static final String NAME = "trainworks_track_graph";

    private final TrackGraph graph = new TrackGraph(this::setDirty);

    public static TrackGraphSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TrackGraphSavedData::load, TrackGraphSavedData::new, NAME);
    }

    public TrackGraph graph() {
        return graph;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("NextNodeId", graph.nextNodeId());
        tag.putLong("NextEdgeId", graph.nextEdgeId());

        ListTag nodesTag = new ListTag();
        for (Node node : graph.nodes().values()) {
            nodesTag.add(node.save());
        }
        tag.put("Nodes", nodesTag);

        ListTag edgesTag = new ListTag();
        for (Edge edge : graph.edges().values()) {
            edgesTag.add(edge.save());
        }
        tag.put("Edges", edgesTag);

        return tag;
    }

    public static TrackGraphSavedData load(CompoundTag tag) {
        TrackGraphSavedData data = new TrackGraphSavedData();
        data.graph.setNextNodeId(tag.getLong("NextNodeId"));
        data.graph.setNextEdgeId(tag.getLong("NextEdgeId"));

        ListTag nodesTag = tag.getList("Nodes", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < nodesTag.size(); i++) {
            Node node = Node.load(nodesTag.getCompound(i));
            data.graph.nodes().put(node.id(), node);
        }

        ListTag edgesTag = tag.getList("Edges", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < edgesTag.size(); i++) {
            Edge edge = Edge.load(edgesTag.getCompound(i));
            data.graph.edges().put(edge.id(), edge);
        }

        return data;
    }
}
