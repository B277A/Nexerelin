package exerelin.campaign.intel.groundbattle.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.econ.impl.GroundDefenses;
import com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Items;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.intel.groundbattle.GBConstants;
import exerelin.campaign.intel.groundbattle.GBDataManager.IndustryDef;
import static exerelin.campaign.intel.groundbattle.GroundBattleIntel.getString;
import exerelin.campaign.intel.groundbattle.GroundBattleSide;
import java.util.List;

public class GroundDefensesPlugin extends IndustryForBattlePlugin {
	
	@Override
	public Float getTroopContribution(String type) {
		Float contrib = super.getTroopContribution(type);
		if (contrib == 0) return contrib;
				
		List<SpecialItemData> specialItems = indForBattle.getIndustry().getVisibleInstalledItems();
		for (SpecialItemData item : specialItems) {
			if (item.getId().equals(Items.DRONE_REPLICATOR)) {
				contrib *= ItemEffectsRepo.DRONE_REPLICATOR_BONUS_MULT;
				//Global.getLogger(this.getClass()).info("  Applying drone replicator bonus");
			}
		}
		
		String aiCoreId = indForBattle.getIndustry().getAICoreId();
		if (Commodities.ALPHA_CORE.equals(aiCoreId))
			contrib *= GroundDefenses.ALPHA_CORE_BONUS;
		
		return contrib;
	}
	
	/**
	 * e.g. Bonus of 3 with mult of 2 becomes 1.5
	 * @param bonus
	 * @param mult
	 * @return 
	 */
	protected float multiplyBonus(float bonus, float mult) {
		bonus--;
		bonus *= mult;
		bonus++;
		return bonus;
	}
	
	/**
	 * Should be identical to the method in BaseIndustry.
	 * @param industry
	 * @param commodities
	 * @return
	 */
	protected float getDeficitMult(Industry industry, String ... commodities) {
		float deficit = industry.getMaxDeficit(commodities).two;
		float demand = 0f;
		
		for (String id : commodities) {
			demand = Math.max(demand, industry.getDemand(id).getQuantity().getModifiedInt());
		}
		
		if (deficit < 0) deficit = 0f;
		if (demand < 1) {
			demand = 1;
			deficit = 0f;
		}
		
		float mult = (demand - deficit) / demand;
		if (mult < 0) mult = 0;
		if (mult > 1) mult = 1;
		return mult;
	}
	
	public float getShortageMult() {
		float shortageMult = getDeficitMult(indForBattle.getIndustry(), Commodities.SUPPLIES, Commodities.MARINES, Commodities.HAND_WEAPONS);
		int shortageAbs = indForBattle.getIndustry().getMaxDeficit(Commodities.SUPPLIES, Commodities.MARINES, Commodities.HAND_WEAPONS).two;
		float multFromShortageAbs = 1 - (shortageAbs * GBConstants.ATTRITION_MULT_PER_DEFICIT_UNIT);
		
		if (shortageMult < multFromShortageAbs)
			shortageMult = multFromShortageAbs;
		
		return shortageMult;
	}
	
	@Override
	public void apply() {
		super.apply();
		
		if (indForBattle.isIndustryTrueDisrupted()) {
			return;
		}
		
		IndustryDef def = getDef();
		GroundBattleSide otherSide = indForBattle.getNonHoldingSide();
		float dropMult = def.enemyDropCostMult, bombardMult = def.enemyBombardmentCostMult, attritionFactor = def.dropAttritionFactor;
		if (otherSide.getData().containsKey("ability_ew_active")) {
			float mult = EWAbilityPlugin.GROUND_DEF_EFFECT_MULT;
			dropMult = multiplyBonus(dropMult, mult);
			bombardMult = multiplyBonus(bombardMult, mult);
			attritionFactor *= mult;
		}
		
		float shortageMult = getShortageMult();
		if (shortageMult < 1) {
			attritionFactor *= shortageMult;
			dropMult = multiplyBonus(dropMult, shortageMult);
			bombardMult = multiplyBonus(bombardMult, shortageMult);
		}	
		
		otherSide.getDropCostMod().modifyMult(indForBattle.getIndustry().getId(), dropMult, 
				indForBattle.getName());
		
		otherSide.getBombardmentCostMod().modifyMult(defId, bombardMult, 
				indForBattle.getName());
		
		otherSide.getDropAttrition().modifyFlat(defId, attritionFactor, 
				indForBattle.getName());
	}
	
	@Override
	public void processTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
		super.processTooltip(tooltip, expanded, tooltipParam);
		float shortageMult = this.getShortageMult();
		if (shortageMult < 1) {
			tooltip.addPara("- " + getString("modifierGroundDefShortageMult"), 0, 
					Misc.getNegativeHighlightColor(), String.format("%.2f×", shortageMult));
		}		
	}
}
