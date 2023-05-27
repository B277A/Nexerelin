package exerelin.campaign.ai.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import exerelin.campaign.SectorManager;
import exerelin.campaign.ai.concern.HostileInSharedSystemConcern;
import exerelin.campaign.ai.concern.StrategicConcern;
import exerelin.campaign.econ.FleetPoolManager;
import exerelin.campaign.fleets.InvasionFleetManager;
import exerelin.campaign.intel.fleets.OffensiveFleetIntel;
import exerelin.utilities.NexConfig;
import exerelin.utilities.NexFactionConfig;
import lombok.Getter;

public class PirateRaidAction extends RaidAction {

    @Getter protected FactionAPI proxy;

    @Override
    public boolean generate() {
        proxy = pickProxy(faction);
        if (proxy == null) return false;

        boolean success = super.generate();
        if (success) {
            OffensiveFleetIntel intel = (OffensiveFleetIntel) delegate;
        }
        return success;
    }

    /**
     * Picks a proxy pirate-type faction to conduct the raid on our behalf.
     * @param target Target faction (optional, used for relationship checking).
     * @return
     */
    protected FactionAPI pickProxy(FactionAPI target) {
        WeightedRandomPicker<FactionAPI> picker = new WeightedRandomPicker<>();
        for (String candidateId : SectorManager.getLiveFactionIdsCopy()) {
            NexFactionConfig conf = NexConfig.getFactionConfig(candidateId);
            if (!conf.pirateFaction) continue;
            FactionAPI candidate = Global.getSector().getFaction(candidateId);
            if (target != null) {
                if (!candidate.isHostileTo(target)) continue;
            }

            float weight = getWeightForRelationship(candidateId);
            picker.add(candidate, weight);
        }

        return picker.pick();
     }

    protected float getWeightForRelationship(String factionId) {
        RepLevel rep = ai.getFaction().getRelationshipLevel(factionId);
        if (factionId.equals(Factions.LUDDIC_PATH)) {
            rep = rep.getOneWorse();
        }
        switch (rep) {
            case VENGEFUL:
                return -1;
            default:
                return 1 + 0.5f * (rep.ordinal() - 1);
        }
    }

    @Override
    public MarketAPI pickOriginMarket(MarketAPI target) {
        // same as super method except allows hidden markets and uses the proxy faction
        return InvasionFleetManager.getManager().getSourceMarketForFleet(proxy, target.getLocationInHyperspace(),
                Global.getSector().getEconomy().getMarketsCopy(), true);
    }

    @Override
    public FleetPoolManager.RequisitionParams getFleetPoolRequisitionParams() {
        FleetPoolManager.RequisitionParams rp = new FleetPoolManager.RequisitionParams();
        rp.factionId = ai.getFactionId();   // faction taking the action pays the fleet pool cost, rather than our proxies
        return rp;
    }

    @Override
    public boolean canUse(StrategicConcern concern) {
        // don't get ourselves raided as well as the target
        if (concern instanceof HostileInSharedSystemConcern) return false;

        return super.canUse(concern);
    }
}
