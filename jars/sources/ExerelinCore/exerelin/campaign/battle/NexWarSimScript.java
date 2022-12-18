package exerelin.campaign.battle;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.fs.starfarer.api.impl.campaign.fleets.RouteManager.*;

public class NexWarSimScript {

    public static FactionStrengthReport getFactionStrengthReport(FactionAPI faction, LocationAPI loc) {
        FactionStrengthReport report = new FactionStrengthReport(faction.getId());

        Set<CampaignFleetAPI> seenFleets = new HashSet<CampaignFleetAPI>();
        for (CampaignFleetAPI fleet : loc.getFleets()) {
            if (fleet.getFaction() != faction) continue;
            if (fleet.isStationMode()) continue;
            if (fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_TRADE_FLEET)) continue;
            if (fleet.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_SMUGGLER)) continue;

            if (fleet.isPlayerFleet()) continue;

            report.addEntry(new FactionStrengthReportEntry(fleet));

            seenFleets.add(fleet);
        }

        for (RouteData route : getInstance().getRoutesInLocation(loc)) {
            if (route.getActiveFleet() != null && seenFleets.contains(route.getActiveFleet())) continue;

            OptionalFleetData data = route.getExtra();
            if (data == null) continue;
            if (route.getFactionId() == null) continue;
            if (!faction.getId().equals(route.getFactionId())) continue;

            if (data.strength != null) {
                float mult = 1f;
                if (data.damage != null) mult *= (1f - data.damage);
                report.addEntry(new FactionStrengthReportEntry("Route " + route.toString(), route, (float)Math.round(data.strength * mult)));
            }
        }

        return report;
    }

    public static class FactionStrengthReport {
        public String factionId;
        public List<FactionStrengthReportEntry> entries = new ArrayList<>();
        public float totalStrength;

        public void addEntry(FactionStrengthReportEntry entry) {
            entries.add(entry);
            totalStrength += entry.strength;
        }

        public FactionStrengthReport(String factionId) {
            this.factionId = factionId;
        }
    }

    public static class FactionStrengthReportEntry {
        public String name;
        public CampaignFleetAPI fleet;
        public RouteData route;
        public float strength;

        public FactionStrengthReportEntry(CampaignFleetAPI fleet) {
            this.fleet = fleet;
            name = fleet.getFullName();
            strength = fleet.getEffectiveStrength();
        }

        public FactionStrengthReportEntry(String name, RouteData route, float strength) {
            this.name = name;
            this.route = route;
            this.strength = strength;
        }
    }
}
