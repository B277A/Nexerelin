package exerelin.campaign.ai.concern;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import exerelin.campaign.ai.SAIConstants;
import exerelin.campaign.ai.StrategicAI;
import exerelin.campaign.diplomacy.DiplomacyTraits;
import exerelin.campaign.econ.EconomyInfoHelper;
import exerelin.utilities.NexUtilsMarket;
import exerelin.utilities.StringHelper;
import lombok.Getter;

import java.awt.*;
import java.util.List;
import java.util.*;

public class CommodityCompetitionConcern extends BaseStrategicConcern implements HasCommodityTarget, HasIndustryTarget {

    public static final int MAX_SIMULTANEOUS_CONCERNS = 1;
    public static final int MAX_SIMULTANEOUS_CONCERNS_MONOPOLIST = 3;

    @Getter protected String commodityId;
    @Getter protected int competitorShare;

    @Override
    public boolean generate() {
        // monopolist only
        int max = MAX_SIMULTANEOUS_CONCERNS;
        if (DiplomacyTraits.hasTrait(ai.getFactionId(), DiplomacyTraits.TraitIds.MONOPOLIST))
            max = MAX_SIMULTANEOUS_CONCERNS_MONOPOLIST;

        Set alreadyConcerned = getExistingConcernItems();
        // note: the alreadyConcerned set contains both commodity and faction IDs, so it's actually twice the length of the actual item count
        if (alreadyConcerned.size() >= max * 2) return false;
        String factionId = ai.getFactionId();
        EconomyInfoHelper helper = EconomyInfoHelper.getInstance();

        //Map<String, Integer> imports = EconomyInfoHelper.getInstance().getCommoditiesImportedByFaction(ai.getFaction().getId());
        //Map<String, Integer> trueImports = new HashMap<>();
        //Map<String, Integer> production = EconomyInfoHelper.getInstance().getCommoditiesProducedByFaction(ai.getFaction().getId());

        List<EconomyInfoHelper.ProducerEntry> competitors = helper.getCompetingProducers(factionId, 6, true);
        WeightedRandomPicker<EconomyInfoHelper.ProducerEntry> picker1 = new WeightedRandomPicker<>();

        for (EconomyInfoHelper.ProducerEntry pe : competitors) {
            if (alreadyConcerned.contains(pe.factionId)) continue;
            if (alreadyConcerned.contains(pe.commodityId)) continue;
            picker1.add(pe, pe.output);
        }

        EconomyInfoHelper.ProducerEntry pePicked = picker1.pick();
        if (pePicked == null) return false;

        commodityId = pePicked.commodityId;
        int ourShare = helper.getMarketShare(ai.getFaction(), commodityId);

        List<Pair<FactionAPI, Integer>> competitors2 = helper.getCompetingProducerFactions(factionId, commodityId);

        WeightedRandomPicker<Pair<FactionAPI, Integer>> picker2 = new WeightedRandomPicker<>();
        for (Pair<FactionAPI, Integer> competitorEntry : competitors2) {
            int share = competitorEntry.two;
            if (share < ourShare/2) continue;
            if (share < SAIConstants.MIN_COMPETITOR_SHARE) continue;
            picker2.add(competitorEntry, share);
        }

        Pair<FactionAPI, Integer> result = picker2.pick();
        if (result == null) return false;

        this.competitorShare = result.two;
        faction = result.one;
        updatePriority();
        return true;
    }

    @Override
    public void update() {
        EconomyInfoHelper helper = EconomyInfoHelper.getInstance();
        int ourShare = helper.getMarketShare(ai.getFaction(), commodityId);
        int theirShare = helper.getMarketShare(faction.getId(), getCommodityId());
        if (theirShare < ourShare/2) {
            end();
            return;
        }
        updatePriority();
    }

    protected void updatePriority() {
        priority.modifyFlat("competingShare", competitorShare * 6, StrategicAI.getString("statCompetingShare", true));
        reapplyPriorityModifiers();
    }

    /**
     * Gets a list of the target faction's markets producing the commodity.
     * @return
     */
    @Override
    public List<MarketAPI> getMarkets() {
        List<MarketAPI> markets = new ArrayList<>();
        List<EconomyInfoHelper.ProducerEntry> competitors = EconomyInfoHelper.getInstance().getProducers(
                faction.getId(), commodityId, 3, true);
        for (EconomyInfoHelper.ProducerEntry entry : competitors) {
            markets.add(entry.market);
        }
        return markets;
    }

    @Override
    public List<String> getCommodityIds() {
        return new ArrayList<>(Arrays.asList(new String[] {commodityId}));
    }

    /**
     * Picks a random competing industry that produces the commodity, for actions that require such.
     * Note to self: This caused me so much trouble when it was passing a sabotage target to {@code BuildIndustryAction}
     * for construction, be careful to not let such things happen again.
     * @return
     */
    @Override
    public Industry getTargetIndustry() {

        // First find a market that produces the thing
        MarketAPI tm = null;
        WeightedRandomPicker<MarketAPI> marketPicker = new WeightedRandomPicker<>();
        List<EconomyInfoHelper.ProducerEntry> competitors = EconomyInfoHelper.getInstance().getProducers(
                faction.getId(), commodityId, 3, true);
        for (EconomyInfoHelper.ProducerEntry entry : competitors) {
            marketPicker.add(entry.market, entry.output);
        }
        tm = marketPicker.pick();
        if (tm == null) return null;

        // now look for an industry on that market that produces the thing (or the spaceport)
        WeightedRandomPicker<Industry> industryPicker = new WeightedRandomPicker<>();
        for (Industry ind : tm.getIndustries()) {
            if (ind.getSpec().hasTag(Industries.TAG_SPACEPORT)) {
                industryPicker.add(ind, 6);
                continue;
            }

            int supply = NexUtilsMarket.getIndustrySupply(ind, commodityId);
            int supplyAdjusted = supply - EconomyInfoHelper.getCommodityOutputModifier(commodityId);
            if (supplyAdjusted > 4) {
                industryPicker.add(ind, supplyAdjusted);
            }
        }
        return industryPicker.pick();
    }

    /**
     * Do not use, call {@code getTargetIndustry()} and use that instead.
     * @return null
     */
    @Override
    public String getTargetIndustryId() {
        return null;
    }

    @Override
    public LabelAPI createTooltipDesc(TooltipMakerAPI tooltip, CustomPanelAPI holder, float pad) {
        if (commodityId == null) return null;
        String str = getDef().desc;
        str = StringHelper.substituteToken(str, "$commodity", StringHelper.getCommodityName(commodityId));
        str = StringHelper.substituteToken(str, "$theirShare", competitorShare + "");
        str = StringHelper.substituteFactionTokens(str, faction);
        Color hl = Misc.getHighlightColor();
        LabelAPI label = tooltip.addPara(str, pad, hl, faction.getDisplayName(), competitorShare + "");
        label.setHighlightColors(faction.getBaseUIColor(), hl);
        return label;
    }

    @Override
    public String getIcon() {
        if (commodityId != null) return Global.getSettings().getCommoditySpec(commodityId).getIconName();
        return super.getIcon();
    }

    @Override
    public String getName() {
        return String.format("%s - %s %s", super.getName(), faction.getDisplayName(), StringHelper.getCommodityName(commodityId));
    }

    @Override
    public String getDisplayName() {
        return String.format("%s - %s", super.getName(), faction.getDisplayName());  //, StringHelper.getCommodityName(commodityId));
    }

    @Override
    public boolean isSameAs(StrategicConcern otherConcern, Object param) {
        String commodityId = this.commodityId;
        if (commodityId == null && param instanceof String)
            commodityId = (String)param;

        if (otherConcern instanceof CommodityCompetitionConcern) {
            CommodityCompetitionConcern idc = (CommodityCompetitionConcern)otherConcern;
            return idc.commodityId == commodityId && idc.faction == faction;
        }
        return false;
    }

    @Override
    public Set getExistingConcernItems() {
        Set<String> commoditiesAndFactions = new HashSet<>();
        for (StrategicConcern concern : getExistingConcernsOfSameType()) {
            CommodityCompetitionConcern ccc = (CommodityCompetitionConcern)concern;
            commoditiesAndFactions.add(ccc.commodityId);
            commoditiesAndFactions.add(ccc.faction.getId());
        }
        return commoditiesAndFactions;
    }

    @Override
    public boolean isValid() {
        return commodityId != null && faction != null && competitorShare > 0;
    }
}
