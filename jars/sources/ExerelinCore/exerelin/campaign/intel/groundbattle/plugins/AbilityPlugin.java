package exerelin.campaign.intel.groundbattle.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Pair;
import exerelin.campaign.intel.groundbattle.GBDataManager;
import exerelin.campaign.intel.groundbattle.GBDataManager.AbilityDef;
import exerelin.campaign.intel.groundbattle.GroundBattleAI;
import exerelin.campaign.intel.groundbattle.GroundBattleIntel;
import exerelin.campaign.intel.groundbattle.GroundBattleLog;
import exerelin.campaign.intel.groundbattle.GroundBattleSide;
import exerelin.campaign.intel.groundbattle.IndustryForBattle;
import exerelin.campaign.intel.groundbattle.dialog.AbilityDialogPlugin;
import exerelin.campaign.ui.FramedCustomPanelPlugin;
import exerelin.utilities.NexUtilsGUI;
import exerelin.utilities.StringHelper;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbilityPlugin {
	
	public static float PANEL_HEIGHT = 40;
	public static float PANEL_WIDTH = 320;
	
	protected String id;
	protected GroundBattleSide side;
	protected int cooldown;
	protected transient IndustryForBattle target;
	
	public void init(String id, GroundBattleSide side) {
		this.id = id;
		this.side = side;
	}
	
	public String getId() {
		return id;
	}
	
	public GroundBattleSide getSide() {
		return side;
	}
	
	public GroundBattleIntel getIntel() {
		return side.getIntel();
	}
	
	public AbilityDef getDef() {
		return GBDataManager.getAbilityDef(id);
	}
	
	/**
	 * Gets an ID and a user-facing string explaining why this ability can't be used right now, along with other data.<br/>
	 * If ability can be used, returns null.
	 * @param user
	 * @return
	 */
	public Pair<String, Map<String, Object>> getDisabledReason(PersonAPI user) {
		String id;
		String desc;
		Map<String, Object> params = new HashMap<>();
		
		if (cooldown > 0) {
			id = "cooldown";
			desc = String.format(GroundBattleIntel.getString("ability_cooldown"), cooldown);
			params.put("desc", desc);
			params.put("cooldown", cooldown);
			return new Pair<>(id, params);
		}
		
		int cooldownGlobal = side.getGlobalAbilityCooldown();
		if (cooldownGlobal > 0) {
			id = "cooldownGlobal";
			desc = String.format(GroundBattleIntel.getString("ability_cooldownGlobal"), 
					cooldownGlobal);
			params.put("desc", desc);
			params.put("cooldown", cooldownGlobal);
			return new Pair<>(id, params);
		}		
		
		if (user != null && user.isPlayer()) {
			GroundBattleIntel intel = getSide().getIntel();
			if (!intel.isPlayerInRange()) {
				id = "out_of_range";
				desc = String.format(GroundBattleIntel.getString("ability_outOfRange"), cooldown);
				params.put("desc", desc);
				params.put("cooldown", cooldown);
				return new Pair<>(id, params);
			}
		}
		
		return null;
	}
	
	public boolean targetsIndustry() {
		return false;
	}
	
	public List<IndustryForBattle> getTargetIndustries() {
		return new ArrayList<>();
	}
	
	public void setTarget(Industry target) {
		this.target = getIntel().getIndustryForBattleByIndustry(target);
	}
	
	public void setTarget(IndustryForBattle target) {
		this.target = target;
	}
	
	public void reportTurn() {
		cooldown--;
		if (cooldown < 0) cooldown = 0;
	}
	
	public void activate(InteractionDialogAPI dialog, PersonAPI user) {
		cooldown = getDef().cooldown;
		side.modifyGlobalAbilityCooldown(getDef().cooldownGlobal);
		getIntel().reportAbilityUsed(this, side, user);
	}
	
	public GroundBattleLog logActivation(PersonAPI user) {
		GroundBattleLog log = new GroundBattleLog(getIntel(), 
				GroundBattleLog.TYPE_ABILITY_USED, getIntel().getTurnNum());
		log.params.put("person", user);
		log.params.put("isAttacker", side.isAttacker());
		log.params.put("ability", getDef());
		log.params.put("industry", target);
		getIntel().addLogEvent(log);
		return log;
	}
	
	public void playUISound() {
		if (getDef().sound != null) {
			Global.getSoundPlayer().playUISound(getDef().sound, 1, 1);
		}
	}
	
	/**
	 * If false, dialog sticks around after ability and requires an option click to close.
	 * @return
	 */
	public boolean shouldCloseDialogOnActivate() {
		return true;
	}
	
	/**
	 * Not supported yet, intended to get whether the ability should require a confirmation to activate.
	 * @return
	 */
	public boolean hasActivateConfirmation() {
		return false;
	}
	
	/**
	 * Generally used for text printed to ability use dialog when opened.
	 * @param dialog
	 */
	public abstract void dialogAddIntro(InteractionDialogAPI dialog);
	
	/**
	 * Do stuff when the dialog requests confirmation of ability use.
	 * @param dialog
	 */
	public void dialogAddConfirmation(InteractionDialogAPI dialog) {
		
	}
	
	/**
	 * Generally called after {@code dialogAddIntro}, prints ability cooldown information.
	 * @param dialog
	 */
	public void addCooldownDialogText(InteractionDialogAPI dialog) {
		TextPanelAPI text = dialog.getTextPanel();
		Color h = Misc.getHighlightColor();
		
		text.setFontSmallInsignia();
		int cooldown = getDef().cooldown, cooldownGlobal = getDef().cooldownGlobal;
		if (cooldown > 0)
			text.addPara(GroundBattleIntel.getString("ability_cooldown"), h, cooldown + "");
		
		if (cooldownGlobal > 0)
			text.addPara(GroundBattleIntel.getString("ability_cooldownGlobal"), h,
					cooldownGlobal + "");
		text.setFontInsignia();
	}
	
	public abstract void generateTooltip(TooltipMakerAPI tooltip);
	
	/**
	 * Called when the ability dialog is populating its options.
	 * @param dialog
	 */
	public void addDialogOptions(InteractionDialogAPI dialog) {
		dialog.getOptionPanel().addOption(StringHelper.getString("activate", true), AbilityDialogPlugin.OptionId.ACTIVATE);
	}
	
	/**
	 * Called on an option being selected in dialog.
	 * @param dialog
	 * @param optionData
	 * @return True if the action should be consumed, causing the interaction dialog to take no further action.
	 */
	public boolean processDialogOption(InteractionDialogAPI dialog, Object optionData) {
		return false;
	}
	
	public boolean showIfDisabled(Pair<String, Map<String, Object>> disableReason) {
		return true;
	}
	
	/**
	 * Creates a custom panel to show the ability in the intel screen.
	 * @param parent
	 * @return
	 */
	public CustomPanelAPI createAbilityCard(CustomPanelAPI parent) {
		float pad = 3;
		AbilityPlugin ability = this;
		
		AbilityDef def = ability.getDef();
		String icon = def.icon != null ? def.icon : "graphics/icons/skills/planetary_ops.png";
		Color color = def.color != null ? def.color : Misc.getBasePlayerColor();
		
		float width = PANEL_WIDTH;
		NexUtilsGUI.CustomPanelGenResult gen = NexUtilsGUI.addPanelWithFixedWidthImage(parent, 
				new FramedCustomPanelPlugin(0.5f, Misc.getBasePlayerColor(), true), 
				width, PANEL_HEIGHT, ability.getDef().name, 
				width - PANEL_HEIGHT - 8, 6, 
				icon, PANEL_HEIGHT, 0, 
				color, true, getAbilityTooltip(ability));
		
		TooltipMakerAPI text = (TooltipMakerAPI)gen.elements.get(1);
		Pair<String, Map<String, Object>> reason = ability.getDisabledReason(Global.getSector().getPlayerPerson());
		if (reason == null) {
			text.addButton(StringHelper.getString("use", true), ability, 48, 20, pad);
		} else {
			String desc = (String)reason.two.get("desc");
			text.addPara(desc, Misc.getNegativeHighlightColor(), pad);
		}
		
		return (CustomPanelAPI)gen.panel;
	}
	
	public static TooltipMakerAPI.TooltipCreator getAbilityTooltip(final AbilityPlugin ability) {
		return new TooltipMakerAPI.TooltipCreator() {
				@Override
				public boolean isTooltipExpandable(Object tooltipParam) {
					return false;
				}

				@Override
				public float getTooltipWidth(Object tooltipParam) {
					return 360;
				}

				@Override
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					ability.generateTooltip(tooltip);
				}
		};
	}
	
	/**
	 * Score for which (if any) ability the AI should use this turn.<br/>
	 * Return value must be 5 or higher to use this ability.
	 * @param ai
	 * @return
	 */
	public float getAIUsePriority(GroundBattleAI ai) {
		return 0;
	}
	
	/**
	 * Called by AI to activate the ability. Does not check for usability.
	 * @param ai
	 * @param user
	 * @return True if the ability was successfully used, false otherwise.
	 */
	public boolean aiExecute(GroundBattleAI ai, PersonAPI user) {
		activate(null, user);
		if (side.getIntel().shouldNotify())
			playUISound();
		return true;
	}
	
	/**
	 * Gets the total strength of attacker and defender fleets in support range of the market.
	 * @return
	 */
	protected float[] getNearbyFleetStrengths() {
		float ours = getNearbyFleetStrengths(side.isAttacker());
		float theirs = getNearbyFleetStrengths(!side.isAttacker());
		//Global.getLogger(this.getClass()).info("Our strength: " + ours);
		//Global.getLogger(this.getClass()).info("Their strength: " + theirs);
		return new float[] {ours, theirs};
	}	
	
	/**
	 * Gets the total strength of attacker or defender fleets in support range of the market.
	 * @param attacker
	 * @return
	 */
	protected float getNearbyFleetStrengths(boolean attacker) {
		float str = 0;
		for (CampaignFleetAPI fleet : getIntel().getSupportingFleets(attacker)) {
			str += fleet.getEffectiveStrength();
		}
		return str;
	}
	
	public static AbilityPlugin loadPlugin(GroundBattleSide side, String defId) 
	{
		String className = GBDataManager.getAbilityDef(defId).plugin;
		AbilityPlugin plugin = null;
		
		try {
			ClassLoader loader = Global.getSettings().getScriptClassLoader();
			Class<?> clazz = loader.loadClass(className);
			plugin = (AbilityPlugin)clazz.newInstance();
			plugin.init(defId, side);
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
			Global.getLogger(IndustryForBattlePlugin.class).error("Failed to load market condition plugin " + defId, ex);
		}
		
		return plugin;
	}
}
