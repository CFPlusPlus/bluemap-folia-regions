package io.pfaumc.bluemapfoliaregions;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.TickRegions.TickRegionData;
import io.papermc.paper.threadedregions.TickRegions.TickRegionSectionData;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BlueMapFoliaRegionsPlugin extends JavaPlugin {
    private static final String MARKER_SET_ID = "folia-regions";
    private static final long INITIAL_DELAY_TICKS = 20L;
    private static final long UPDATE_INTERVAL_TICKS = 20L * 5L;

    private final int sectionChunkSize = 1 << TickRegions.getRegionChunkShift();
    private final int sectionBlockSize = sectionChunkSize * 16;
    private final ConcurrentMap<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown = false;

    @Override
    public void onEnable() {
        this.shuttingDown = false;
        BlueMapAPI.onEnable(this::onBlueMapEnable);
        BlueMapAPI.onDisable(this::onBlueMapDisable);
    }

    @Override
    public void onDisable() {
        this.shuttingDown = true;
        cancelAllTasks();
        BlueMapAPI.getInstance().ifPresent(this::removeAllMarkerSets);
    }

    private void onBlueMapEnable(BlueMapAPI api) {
        if (this.shuttingDown) {
            return;
        }

        cancelAllTasks();
        for (BlueMapMap map : api.getMaps()) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    this,
                    (t) -> {
                        if (!this.shuttingDown && isEnabled()) {
                            updateRegionMarkers(map);
                        }
                    },
                    INITIAL_DELAY_TICKS,
                    UPDATE_INTERVAL_TICKS
            );

            ScheduledTask previous = this.tasks.put(map.getId(), task);
            if (previous != null) {
                previous.cancel();
            }
        }
    }

    private void onBlueMapDisable(BlueMapAPI api) {
        cancelAllTasks();
        removeAllMarkerSets(api);
    }

    private void cancelAllTasks() {
        for (ScheduledTask task : this.tasks.values()) {
            task.cancel();
        }
        this.tasks.clear();
    }

    private void removeAllMarkerSets(BlueMapAPI api) {
        for (BlueMapMap map : api.getMaps()) {
            removeMarkerSet(map);
        }
    }

    private void removeMarkerSet(BlueMapMap map) {
        try {
            Bukkit.getGlobalRegionScheduler().run(this, (t) -> map.getMarkerSets().remove(MARKER_SET_ID));
        } catch (IllegalPluginAccessException ignored) {
            map.getMarkerSets().remove(MARKER_SET_ID);
        }
    }

    private void updateRegionMarkers(BlueMapMap map) {
        MarkerSet markerSet = MarkerSet.builder()
                .label("Folia Tick-Regionen")
                .defaultHidden(true)
                .toggleable(true)
                .build();

        String id = map.getWorld().getId();
        Optional<World> worldOptional = resolveWorld(id);
        if (worldOptional.isEmpty()) {
            getLogger().warning("World not found for BlueMap world id: " + id);
            map.getMarkerSets().remove(MARKER_SET_ID);
            return;
        }

        World world = worldOptional.get();
        ThreadedRegionizer<TickRegionData, TickRegionSectionData> regioniser =
                ((CraftWorld) world).getHandle().regioniser;

        Map<String, ShapeMarker> markers = createMarkers(regioniser);
        markerSet.getMarkers().putAll(markers);
        map.getMarkerSets().put(MARKER_SET_ID, markerSet);
    }

    private Optional<World> resolveWorld(String id) {
        int hashIndex = id.indexOf('#');
        if (hashIndex != -1) {
            String worldName = id.substring(0, hashIndex);
            World byName = Bukkit.getWorld(worldName);
            if (byName != null) {
                return Optional.of(byName);
            }
        }

        try {
            UUID uuid = UUID.fromString(id);
            World byUuid = Bukkit.getWorld(uuid);
            if (byUuid != null) {
                return Optional.of(byUuid);
            }
        } catch (IllegalArgumentException ignored) {
            // The BlueMap world id is not a UUID.
        }

        return Optional.ofNullable(Bukkit.getWorld(id));
    }

    private Map<String, ShapeMarker> createMarkers(ThreadedRegionizer<TickRegionData, TickRegionSectionData> regioniser) {
        List<RegionSnapshot> snapshots = Collections.synchronizedList(new ArrayList<>());
        regioniser.computeForAllRegions((region) -> snapshots.add(toSnapshot(region)));

        Map<String, ShapeMarker> markers = new HashMap<>(snapshots.size());
        for (RegionSnapshot snapshot : snapshots) {
            if (snapshot.centerChunk() == null || snapshot.sections().isEmpty()) {
                continue;
            }

            List<Vector2d> points = getSectionPoints(snapshot.sections());
            if (points.size() < 3) {
                continue;
            }

            ChunkPos centerChunk = snapshot.centerChunk();
            String markerId = "region-" + centerChunk.x + "-" + centerChunk.z;
            String label = "Region[" + centerChunk.x + "," + centerChunk.z + "]";
            Shape shape = new Shape(points);

            String detail =
                    "Sektionen: " + snapshot.sections().size() + "<br>" +
                    "Chunks: " + snapshot.chunkCount() + "<br>" +
                    "Entitaeten: " + snapshot.entityCount() + "<br>" +
                    "Spieler: " + snapshot.playerCount();

            ShapeMarker marker = ShapeMarker.builder()
                    .shape(shape, 80)
                    .label(label)
                    .lineColor(new Color(155, 70, 255, 1.0f))
                    .fillColor(new Color(210, 170, 255, 0.35f))
                    .depthTestEnabled(false)
                    .build();
            marker.setDetail(detail);

            markers.put(markerId, marker);
        }
        return markers;
    }

    private RegionSnapshot toSnapshot(ThreadedRegion<TickRegionData, TickRegionSectionData> region) {
        ChunkPos centerChunk = region.getCenterChunk();
        List<Long> sections = List.copyOf(region.getOwnedSections());
        TickRegions.RegionStats stats = region.getData().getRegionStats();
        return new RegionSnapshot(
                centerChunk,
                sections,
                stats.getChunkCount(),
                stats.getEntityCount(),
                stats.getPlayerCount()
        );
    }

    private List<Vector2d> getSectionPoints(List<Long> sections) {
        Set<SectionCoord> sectionCoords = new HashSet<>(sections.size());
        for (long sectionKey : sections) {
            sectionCoords.add(new SectionCoord(getChunkX(sectionKey), getChunkZ(sectionKey)));
        }

        List<GridPoint> outline = extractLargestOutline(sectionCoords);
        List<Vector2d> points = new ArrayList<>(outline.size());
        for (GridPoint point : outline) {
            points.add(Vector2d.from(
                    (double) point.x() * sectionBlockSize,
                    (double) point.z() * sectionBlockSize
            ));
        }
        return points;
    }

    private static List<GridPoint> extractLargestOutline(Set<SectionCoord> sections) {
        if (sections.isEmpty()) {
            return List.of();
        }

        Map<GridPoint, List<GridPoint>> outgoing = new HashMap<>();
        for (SectionCoord section : sections) {
            int x = section.x();
            int z = section.z();

            if (!sections.contains(new SectionCoord(x, z - 1))) {
                addEdge(outgoing, new GridPoint(x + 1, z), new GridPoint(x, z));
            }
            if (!sections.contains(new SectionCoord(x + 1, z))) {
                addEdge(outgoing, new GridPoint(x + 1, z + 1), new GridPoint(x + 1, z));
            }
            if (!sections.contains(new SectionCoord(x, z + 1))) {
                addEdge(outgoing, new GridPoint(x, z + 1), new GridPoint(x + 1, z + 1));
            }
            if (!sections.contains(new SectionCoord(x - 1, z))) {
                addEdge(outgoing, new GridPoint(x, z), new GridPoint(x, z + 1));
            }
        }

        Set<Edge> visited = new HashSet<>();
        List<List<GridPoint>> loops = new ArrayList<>();
        for (Map.Entry<GridPoint, List<GridPoint>> entry : outgoing.entrySet()) {
            for (GridPoint to : entry.getValue()) {
                Edge start = new Edge(entry.getKey(), to);
                if (visited.contains(start)) {
                    continue;
                }

                List<GridPoint> loop = traceLoop(outgoing, visited, start);
                if (loop.size() >= 3) {
                    loops.add(simplifyLoop(loop));
                }
            }
        }

        return loops.stream()
                .max(Comparator.comparingDouble(BlueMapFoliaRegionsPlugin::polygonAreaAbs))
                .orElse(List.of());
    }

    private static List<GridPoint> traceLoop(Map<GridPoint, List<GridPoint>> outgoing, Set<Edge> visited, Edge start) {
        List<GridPoint> loop = new ArrayList<>();
        GridPoint from = start.from();
        GridPoint to = start.to();

        while (true) {
            Edge current = new Edge(from, to);
            if (!visited.add(current)) {
                break;
            }
            loop.add(from);

            List<GridPoint> candidates = outgoing.getOrDefault(to, List.of());
            GridPoint next = selectNext(to, from, candidates, visited);
            if (next == null) {
                break;
            }

            from = to;
            to = next;
            if (from.equals(start.from()) && to.equals(start.to())) {
                break;
            }
        }
        return loop;
    }

    private static GridPoint selectNext(
            GridPoint current,
            GridPoint previous,
            List<GridPoint> candidates,
            Set<Edge> visited
    ) {
        GridPoint best = null;
        int bestPriority = Integer.MAX_VALUE;
        int inDirection = directionIndex(current.x() - previous.x(), current.z() - previous.z());

        for (GridPoint candidate : candidates) {
            Edge edge = new Edge(current, candidate);
            if (visited.contains(edge)) {
                continue;
            }

            int outDirection = directionIndex(candidate.x() - current.x(), candidate.z() - current.z());
            int turn = (outDirection - inDirection + 4) % 4;
            int priority = switch (turn) {
                case 3 -> 0; // prefer left turn
                case 0 -> 1; // then straight
                case 1 -> 2; // then right
                default -> 3; // avoid going back
            };

            if (priority < bestPriority) {
                bestPriority = priority;
                best = candidate;
            }
        }
        return best;
    }

    private static int directionIndex(int dx, int dz) {
        if (dx == 1 && dz == 0) {
            return 0;
        }
        if (dx == 0 && dz == 1) {
            return 1;
        }
        if (dx == -1 && dz == 0) {
            return 2;
        }
        if (dx == 0 && dz == -1) {
            return 3;
        }
        throw new IllegalArgumentException("Edge is not axis-aligned: (" + dx + ", " + dz + ")");
    }

    private static List<GridPoint> simplifyLoop(List<GridPoint> loop) {
        if (loop.size() < 3) {
            return loop;
        }

        List<GridPoint> simplified = new ArrayList<>(loop.size());
        for (int i = 0; i < loop.size(); i++) {
            GridPoint previous = loop.get((i - 1 + loop.size()) % loop.size());
            GridPoint current = loop.get(i);
            GridPoint next = loop.get((i + 1) % loop.size());

            int dx1 = current.x() - previous.x();
            int dz1 = current.z() - previous.z();
            int dx2 = next.x() - current.x();
            int dz2 = next.z() - current.z();

            if (dx1 == dx2 && dz1 == dz2) {
                continue;
            }
            simplified.add(current);
        }

        return simplified.size() >= 3 ? simplified : loop;
    }

    private static void addEdge(Map<GridPoint, List<GridPoint>> outgoing, GridPoint from, GridPoint to) {
        outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
    }

    private static double polygonAreaAbs(List<GridPoint> points) {
        if (points.size() < 3) {
            return 0D;
        }

        long area2 = 0L;
        for (int i = 0; i < points.size(); i++) {
            GridPoint current = points.get(i);
            GridPoint next = points.get((i + 1) % points.size());
            area2 += (long) current.x() * next.z() - (long) next.x() * current.z();
        }
        return Math.abs(area2) / 2.0D;
    }

    private static int getChunkX(long chunkKey) {
        return (int) chunkKey;
    }

    private static int getChunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    private record RegionSnapshot(
            ChunkPos centerChunk,
            List<Long> sections,
            int chunkCount,
            int entityCount,
            int playerCount
    ) {}

    private record SectionCoord(int x, int z) {}

    private record GridPoint(int x, int z) {}

    private record Edge(GridPoint from, GridPoint to) {}
}
