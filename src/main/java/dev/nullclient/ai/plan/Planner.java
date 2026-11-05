package dev.nullclient.ai.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backward-chaining planner. To obtain an item it finds a way (gather, craft or smelt), recursively plans
 * the prerequisites (tools, ingredients, stations, fuel) against a simulated inventory, and emits steps in
 * execution order. Smelting is split into "load furnace" and "collect"; collect steps are then moved as late as
 * possible so the bot can work while the furnace cooks. All game rules come from {@link Knowledge}.
 */
public final class Planner {
    public static final class PlanException extends RuntimeException {
        public PlanException(String message) {
            super(message);
        }
    }

    private final Knowledge k = Knowledge.get();
    private final Map<String, Integer> inv;
    private final Set<String> stations;
    private final List<Step> steps = new ArrayList<>();
    private int depth;

    private Planner(Map<String, Integer> inv, Set<String> stations) {
        this.inv = inv;
        this.stations = stations;
    }

    /** @param stations station blocks already available in the world ("crafting_table", "furnace") */
    public static List<Step> plan(Map<String, Integer> have, Set<String> stations, String item, int count) {
        Planner p = new Planner(new HashMap<>(have), new HashSet<>(stations));
        p.need(item, count);
        return defer(p.steps);
    }

    private static List<Step> defer(List<Step> in) {
        List<Step> out = new ArrayList<>(in);
        for (int i = 0; i < out.size(); i++) {
            Step c = out.get(i);
            if (c.kind() != Step.Kind.SMELT_COLLECT) {
                continue;
            }
            int limit = out.size();
            for (int j = i + 1; j < out.size(); j++) {
                Step o = out.get(j);
                if (o.kind() == Step.Kind.SMELT_START || consumes(o, c.item())) {
                    limit = j;
                    break;
                }
            }
            if (limit - 1 > i) {
                out.remove(i);
                out.add(limit - 1, c);
                i--;
            }
        }
        return out;
    }

    private static boolean consumes(Step s, String item) {
        Knowledge k = Knowledge.get();
        if (s.kind() == Step.Kind.CRAFT) {
            for (String token : s.recipe().ingredients().keySet()) {
                if (k.members(token).contains(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void need(String token, int count) {
        if (++depth > 80) {
            throw new PlanException("plan too deep near " + token + " (recipe cycle?)");
        }
        int have = Inv.count(inv, token);
        if (have < count) {
            int missing = count - have;
            List<String> members = k.members(token);
            if (members.isEmpty()) {
                throw new PlanException("unknown tag " + token);
            }
            String item = members.get(0);
            Knowledge.Gather g = k.gatherFor(item);
            if (g != null) {
                planGather(g, token, item, count, missing);
            } else {
                Knowledge.Recipe r = k.recipeFor(item);
                if (r != null) {
                    planCraft(r, item, missing);
                } else {
                    Knowledge.Smelt s = k.smeltFor(item);
                    if (s == null) {
                        throw new PlanException("no known way to obtain " + token);
                    }
                    planSmelt(s, item, missing);
                }
            }
        }
        depth--;
    }

    private boolean hasAny(List<String> items) {
        for (String i : items) {
            if (Inv.count(inv, i) > 0) {
                return true;
            }
        }
        return false;
    }

    private void consume(String token, int n) {
        int left = n;
        for (String m : k.members(token)) {
            int take = Math.min(left, inv.getOrDefault(m, 0));
            if (take > 0) {
                inv.merge(m, -take, Integer::sum);
                left -= take;
            }
        }
    }

    private void planGather(Knowledge.Gather g, String token, String item, int count, int missing) {
        if (!g.tool.isEmpty() && !hasAny(g.tool)) {
            need(g.tool.get(0), 1);
        }
        steps.add(new Step(Step.Kind.GATHER, token, count, g, null, null));
        inv.merge(item, missing, Integer::sum);
    }

    private void planCraft(Knowledge.Recipe r, String item, int missing) {
        int runs = (int) Math.ceil(missing / (double) r.count);
        if (r.grid() == 3 && !stations.contains("crafting_table") && Inv.count(inv, "crafting_table") == 0) {
            need("crafting_table", 1);
        }
        for (Map.Entry<String, Integer> e : r.ingredients().entrySet()) {
            need(e.getKey(), e.getValue() * runs);
            consume(e.getKey(), e.getValue() * runs);
        }
        steps.add(new Step(Step.Kind.CRAFT, r.out, runs, null, r, null));
        inv.merge(item, runs * r.count, Integer::sum);
    }

    private void planSmelt(Knowledge.Smelt s, String item, int n) {
        need(s.in, n);
        consume(s.in, n);
        Knowledge.Fuel chosen = null;
        for (Knowledge.Fuel f : k.fuel()) {
            if (Inv.count(inv, f.item) * f.burn >= n) {
                chosen = f;
                break;
            }
        }
        if (chosen != null) {
            consume(chosen.item, (int) Math.ceil(n / chosen.burn));
        } else {
            Knowledge.Fuel coal = k.fuel().get(0);
            int amount = (int) Math.ceil(n / coal.burn);
            need(coal.item, amount);
            consume(coal.item, amount);
        }
        if (!stations.contains("furnace") && Inv.count(inv, "furnace") == 0) {
            need("furnace", 1);
        }
        steps.add(new Step(Step.Kind.SMELT_START, s.out, n, null, null, s));
        steps.add(new Step(Step.Kind.SMELT_COLLECT, s.out, n, null, null, s));
        inv.merge(item, n, Integer::sum);
    }
}