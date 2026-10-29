package com.moveinsync.evfleet.core.bound;

import com.moveinsync.evfleet.core.model.FleetPhysics;
import com.moveinsync.evfleet.core.model.Trip;
import com.moveinsync.evfleet.core.model.Vehicle;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Trips that cannot be served even if batteries were unlimited, counting empty drives and where
 * each cab starts.
 *
 * <p>Build a bipartite graph. Left: every cab (its start position and time) and every trip (its
 * drop point and end time). Right: every trip. Edge x -> j when, at top speed, something leaving x
 * reaches trip j's pickup in time (leaving at once or after traffic clears). In any real plan each served trip has exactly one predecessor
 * (a cab start or another trip) and each predecessor is used at most once, so the served trips
 * form a matching. Hence backup trips >= T - (maximum matching).
 *
 * <p>Matching by Hopcroft-Karp: O(E sqrt(T + V)), E up to T^2 / 2 + T x V edges.
 */
final class ChainCoverBound {

    private ChainCoverBound() {
    }

    static int unservable(List<Trip> input, List<Vehicle> vehicles, FleetPhysics physics) {
        Trip[] trips = input.toArray(new Trip[0]);
        Arrays.sort(trips, Comparator.comparingInt(Trip::startTime));
        int n = trips.length;
        int cabs = vehicles.size();
        int[] end = new int[n];
        for (int i = 0; i < n; i++) end[i] = trips[i].startTime() + physics.tripMinutes(trips[i]);

        int[][] adj = new int[n + cabs][];
        int[] buf = new int[n];
        for (int c = 0; c < cabs; c++) {
            Vehicle v = vehicles.get(c);
            int k = 0;
            for (int j = 0; j < n; j++) {
                double km = physics.geo().roadKm(v.location(), trips[j].start());
                if (physics.geo().earliestArrival(km, v.availableFrom()) <= trips[j].startTime()) buf[k++] = j;
            }
            adj[n + c] = Arrays.copyOf(buf, k);
        }
        for (int i = 0; i < n; i++) {
            int k = 0;
            for (int j = 0; j < n; j++) {
                if (i == j || trips[j].startTime() < end[i]) continue;
                double km = physics.geo().roadKm(trips[i].end(), trips[j].start());
                if (physics.geo().earliestArrival(km, end[i]) <= trips[j].startTime()) buf[k++] = j;
            }
            adj[i] = Arrays.copyOf(buf, k);
        }
        return n - hopcroftKarp(adj, n);
    }

    /** {@code adj} has one row per left vertex; right vertices are 0..rightSize-1. */
    private static int hopcroftKarp(int[][] adj, int rightSize) {
        int[] matchL = new int[adj.length];
        int[] matchR = new int[rightSize];
        int[] dist = new int[adj.length];
        Arrays.fill(matchL, -1);
        Arrays.fill(matchR, -1);
        int matching = 0;
        while (bfs(adj, matchL, matchR, dist)) {
            for (int u = 0; u < adj.length; u++) {
                if (matchL[u] == -1 && dfs(u, adj, matchL, matchR, dist)) matching++;
            }
        }
        return matching;
    }

    private static boolean bfs(int[][] adj, int[] matchL, int[] matchR, int[] dist) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        boolean foundFree = false;
        for (int u = 0; u < adj.length; u++) {
            if (matchL[u] == -1) {
                dist[u] = 0;
                queue.add(u);
            } else {
                dist[u] = Integer.MAX_VALUE;
            }
        }
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (int v : adj[u]) {
                int w = matchR[v];
                if (w == -1) {
                    foundFree = true;
                } else if (dist[w] == Integer.MAX_VALUE) {
                    dist[w] = dist[u] + 1;
                    queue.add(w);
                }
            }
        }
        return foundFree;
    }

    private static boolean dfs(int u, int[][] adj, int[] matchL, int[] matchR, int[] dist) {
        for (int v : adj[u]) {
            int w = matchR[v];
            if (w == -1 || (dist[w] == dist[u] + 1 && dfs(w, adj, matchL, matchR, dist))) {
                matchL[u] = v;
                matchR[v] = u;
                return true;
            }
        }
        dist[u] = Integer.MAX_VALUE;
        return false;
    }
}
