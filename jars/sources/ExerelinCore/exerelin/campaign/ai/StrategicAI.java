package exerelin.campaign.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.SectorManager;
import exerelin.campaign.ai.concern.StrategicConcern;
import exerelin.plugins.ExerelinModPlugin;
import exerelin.utilities.NexConfig;
import exerelin.utilities.StringHelper;
import lombok.Getter;
import lombok.extern.log4j.Log4j;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * High-level strategy AI for factions.
 */
@Log4j
public class StrategicAI extends BaseIntelPlugin {

	/*
		How the AI works:
			Each strategy AI has an economic module, military module, diplomacy module, and executive module.
			Update interval is a month long.
			Every update ("strategy meeting"), the econ, mil and diplo modules search for "concerns" (issues of note facing the faction),
			and check if previously generated concerns are still relevant.
			The executive module then picks two of the most important concerns and attempts to generates an "action" for each.

		Examples of concerns: revanchist claim on a planet, commodity competition from another faction, desire to butter up another faction, etc.
		Examples of actions: diplomatic actions, invasions, raids, agent sabotage
	 */

	public static final String MEMORY_KEY = "$nex_strategicAI";
	public static final String UPDATE_NEW_CONCERNS = "new_concerns";
	public static final float MARGIN = 40;
	public static final Object BUTTON_MEETING = new Object();
	//public static final Long lastReportTimestamp;

	@Getter	protected final FactionAPI faction;
	//protected transient TooltipMakerAPI savedReport;

	@Getter protected EconomicAIModule econModule;
	@Getter protected DiplomaticAIModule diploModule;
	@Getter protected MilitaryAIModule milModule;
	@Getter protected ExecutiveAIModule execModule;

	@Deprecated protected List<StrategicConcern> existingConcerns = new ArrayList<>();
	protected transient List<StrategicConcern> lastAddedConcerns = new ArrayList<>();
	protected transient List<StrategicConcern> lastRemovedConcerns = new ArrayList<>();
	protected IntervalUtil interval = new IntervalUtil(29, 31);
	protected IntervalUtil intervalShort = new IntervalUtil(0.48f, 0.52f);
	@Getter protected float daysSinceLastUpdate;


	public StrategicAI(FactionAPI faction) {
		this.faction = faction;
	}
	
	public StrategicAI init() {
		Global.getSector().getIntelManager().addIntel(this, true);
		Global.getSector().addScript(this);
		faction.getMemoryWithoutUpdate().set(MEMORY_KEY, this);

		econModule = new EconomicAIModule(this, StrategicDefManager.ModuleType.ECONOMIC);
		diploModule = new DiplomaticAIModule(this, StrategicDefManager.ModuleType.DIPLOMATIC);
		milModule = new MilitaryAIModule(this, StrategicDefManager.ModuleType.MILITARY);
		execModule = new ExecutiveAIModule(this);
		econModule.init();
		diploModule.init();
		milModule.init();

		econModule.findConcerns();
		diploModule.findConcerns();
		milModule.findConcerns();

		return this;
	}

	protected Object readResolve() {
		lastAddedConcerns = new ArrayList<>();
		lastRemovedConcerns = new ArrayList<>();
		if (execModule == null) execModule = new ExecutiveAIModule(this);
		if (intervalShort == null) intervalShort = new IntervalUtil(0.48f, 0.52f);
		return this;
	}
	
	public static StrategicAI getAI(String factionId) {
		return (StrategicAI)Global.getSector().getFaction(factionId).getMemoryWithoutUpdate().get(MEMORY_KEY);
	}

	public String getFactionId() {
		return faction.getId();
	}

	public void addConcerns(Collection<StrategicConcern> concerns) {
		//existingConcerns.addAll(concerns);
		lastAddedConcerns.addAll(concerns);
	}

	@Override
	protected void advanceImpl(float amount) {
		float days = Global.getSector().getClock().convertToDays(amount);

		intervalShort.advance(days);
		if (intervalShort.intervalElapsed()) {
			float days2 = intervalShort.getElapsed();
			econModule.advance(days2);
			milModule.advance(days2);
			diploModule.advance(days2);
			execModule.advance(days2);
		}

		interval.advance(days);
		if (!interval.intervalElapsed()) return;
		daysSinceLastUpdate = interval.getElapsed();

		update();
	}

	protected void update() {
		// update existing concerns, remove any if needed
		updateConcerns(econModule);
		updateConcerns(milModule);
		updateConcerns(diploModule);

		// find new concerns
		findConcerns(econModule);
		findConcerns(milModule);
		findConcerns(diploModule);

		// TODO: tell executive module to take action
		execModule.actOnConcerns();

		if (!lastAddedConcerns.isEmpty() || !lastRemovedConcerns.isEmpty()) {
			sendUpdateIfPlayerHasIntel(UPDATE_NEW_CONCERNS, true, false);
			lastAddedConcerns.clear();
			lastRemovedConcerns.clear();
		}

		SAIUtils.reportStrategyMeetingHeld(this);
	}

	protected void findConcerns(StrategicAIModule module) {
		List<StrategicConcern> newConcerns = module.findConcerns();
		if (!newConcerns.isEmpty()) {
			addConcerns(newConcerns);
		}
	}

	protected void updateConcerns(StrategicAIModule module) {
		List<StrategicConcern> removedConcerns = module.updateConcerns();
		if (!removedConcerns.isEmpty()) {
			lastRemovedConcerns.addAll(removedConcerns);
		}
	}

	public List<StrategicConcern> getExistingConcerns() {
		List<StrategicConcern> concerns = new ArrayList<>();
		concerns.addAll(econModule.getCurrentConcerns());
		concerns.addAll(milModule.getCurrentConcerns());
		concerns.addAll(diploModule.getCurrentConcerns());
		return concerns;
	}

	@Override
	protected void notifyEnding() {
		// only the military module is a listener rn
		ListenerManagerAPI listenerMan = Global.getSector().getListenerManager();
		listenerMan.removeListener(this);
		listenerMan.removeListener(econModule);
		listenerMan.removeListener(milModule);
		listenerMan.removeListener(diploModule);
	}

	/**
	 * This could be used to make the interval longer/shorter based on how many factions there are, but I cba to do anything like that rn
	 */
	@Deprecated
	protected void updateInterval() {

	}

	/*
	============================================================================
	// start of GUI stuff
	============================================================================
	*/

	public void generateReport(TooltipMakerAPI tooltip, CustomPanelAPI panel, float width) {
		float pad = 3;
		float opad = 10;
		float sectionPad = 16;

		String str = getString("intelPara_daysToNextMeeting");
		tooltip.addPara(str, opad, Misc.getHighlightColor(), String.format("%.1f", interval.getIntervalDuration() - interval.getElapsed()));
		if (ExerelinModPlugin.isNexDev) {
			tooltip.addButton("Force meeting", BUTTON_MEETING, 128, 24, pad);
		}

		tooltip.addSectionHeading(getString("intelHeader_economy"), faction.getBaseUIColor(), faction.getDarkUIColor(), Alignment.MID, sectionPad);
		try {
			econModule.generateReport(tooltip, panel, width);
		} catch (Exception ex) {
			log.error("Failed to generate economy report", ex);
		}

		tooltip.setParaInsigniaLarge();
		tooltip.addSectionHeading(getString("intelHeader_military"), faction.getBaseUIColor(), faction.getDarkUIColor(), Alignment.MID, sectionPad);
		tooltip.setParaFontDefault();
		try {
			milModule.generateReport(tooltip, panel, width);
		} catch (Exception ex) {
			log.error("Failed to generate military report", ex);
		}

		tooltip.addSectionHeading(getString("intelHeader_diplomacy"), faction.getBaseUIColor(), faction.getDarkUIColor(), Alignment.MID, sectionPad);
		try {
			diploModule.generateReport(tooltip, panel, width);
		} catch (Exception ex) {
			log.error("Failed to generate diplomacy report", ex);
		}

		tooltip.addSectionHeading(getString("intelHeader_executive"), faction.getBaseUIColor(), faction.getDarkUIColor(), Alignment.MID, sectionPad);
		try {
			execModule.generateReport(tooltip, panel, width);
		} catch (Exception ex) {
			log.error("Failed to generate executive report", ex);
		}
	}
	
	public void displayReport(TooltipMakerAPI tooltip, CustomPanelAPI panel, float width, float pad)
	{
		width -= MARGIN;
		generateReport(tooltip, panel, width);
	}
	
	@Override
	public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
		TooltipMakerAPI superheaderHolder = panel.createUIElement(width/2, 40, false);
		TooltipMakerAPI superheader = superheaderHolder.beginImageWithText(getIcon(), 40);
		superheader.setParaOrbitronVeryLarge();
		superheader.addPara(getName(), 3);
		superheaderHolder.addImageWithText(3);
		
		panel.addUIElement(superheaderHolder).inTL(width*0.3f, 0);
		
		TooltipMakerAPI tableHolder = panel.createUIElement(width, 600, true);
		
		displayReport(tableHolder, panel, width, 10);
		panel.addUIElement(tableHolder).inTL(3, 48);
	}

	@Override
	protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode, boolean isUpdate, Color tc, float initPad) {
		Object param = getListInfoParam();
		if (param == UPDATE_NEW_CONCERNS) {
			info.addPara(getString("intelBulletUpdate"), initPad);
			int numNewConcerns = lastAddedConcerns.size();
			if (numNewConcerns > 0) info.addPara(getString("intelBulletUpdateAdd"), 0, tc,
					numNewConcerns + "", lastAddedConcerns.toString());
			int numRemovedConcerns = lastRemovedConcerns.size();
			if (numRemovedConcerns > 0) info.addPara(getString("intelBulletUpdateRemove"), 0, tc,
					numRemovedConcerns + "", lastRemovedConcerns.toString());
		}
	}

	@Override
	public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
		if (buttonId == BUTTON_MEETING) {
			update();
			ui.updateUIForItem(this);
		}
	}

	@Override
	public boolean hasSmallDescription() {
		return false;
	}

	@Override
	public boolean hasLargeDescription() {
		return true;
	}
	
	@Override
	public String getIcon() {
		return getFactionForUIColors().getCrest();
	}
	
	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(StringHelper.getString("exerelin_misc", "intelTagDebug"));
		//tags.add(DiplomacyProfileIntel.getString("intelTag"));
		return tags;
	}	

	@Override
	public IntelSortTier getSortTier() {
		return IntelSortTier.TIER_1;
	}
	
	@Override
	protected String getName() {
		return getFactionForUIColors().getDisplayName() + " Strategic AI";
	}

	@Override
	public FactionAPI getFactionForUIColors() {
		return faction;
	}

	@Override
	public boolean isHidden() {
		return !ExerelinModPlugin.isNexDev;
	}

	public static String getString(String id) {
		return getString(id, false);
	}

	public static String getString(String id, boolean ucFirst) {
		return StringHelper.getString("nex_strategicAI", id, ucFirst);
	}

	// runcode exerelin.campaign.ai.StrategicAI.addIntel("tritachyon");
	public static StrategicAI addIntel(String factionId) {
		StrategicAI ai = new StrategicAI(Global.getSector().getFaction(factionId));
		ai.init();
		return ai;
	}

	public static void addAIIfNeeded(String factionId) {
		if (getAI(factionId) == null) {
			addIntel(factionId);
		}
	}

	public static void removeAI(String factionId) {
		StrategicAI ai = getAI(factionId);
		if (ai != null) {
			ai.endImmediately();
			Global.getSector().getFaction(factionId).getMemoryWithoutUpdate().unset(MEMORY_KEY);
		}
	}

	public static void addAIsIfNeeded() {
		for (String factionId : SectorManager.getLiveFactionIdsCopy()) {
			if (factionId.equals(Factions.PLAYER)) continue;
			if (NexConfig.getFactionConfig(factionId).pirateFaction) continue;
			addAIIfNeeded(factionId);
		}
	}

	public static void removeAIs() {
		for (String factionId : SectorManager.getLiveFactionIdsCopy()) {
			if (factionId.equals(Factions.PLAYER)) continue;
			removeAI(factionId);
		}
	}
	
	// runcode exerelin.campaign.ai.StrategicAI.purgeConcerns();
	public static void purgeConcerns() {
		for (String factionId : SectorManager.getLiveFactionIdsCopy()) {
			StrategicAI ai = getAI(factionId);
			if (ai != null) {
				for (StrategicConcern concern : ai.getExistingConcerns()) {
					concern.end();
				}
				ai.econModule.currentConcerns.clear();
				ai.milModule.currentConcerns.clear();
				ai.diploModule.currentConcerns.clear();
				ai.existingConcerns.clear();
			}
		}
	}
}
