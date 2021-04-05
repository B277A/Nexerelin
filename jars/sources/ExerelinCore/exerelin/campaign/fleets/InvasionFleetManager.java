package exerelin.campaign.fleets;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySourceType;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactory.PatrolType;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathBaseIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathCells;
import com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathCellsIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivity;
import com.fs.starfarer.api.impl.campaign.intel.inspection.HegemonyInspectionManager;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import exerelin.campaign.DiplomacyManager;
import exerelin.campaign.PlayerFactionStore;
import exerelin.campaign.SectorManager;
import exerelin.campaign.StatsTracker;
import exerelin.campaign.diplomacy.DiplomacyTraits;
import exerelin.campaign.diplomacy.DiplomacyTraits.TraitIds;
import exerelin.campaign.econ.EconomyInfoHelper;
import exerelin.campaign.intel.invasion.InvasionIntel;
import exerelin.campaign.intel.raid.NexRaidIntel;
import exerelin.campaign.intel.fleets.OffensiveFleetIntel;
import exerelin.campaign.intel.invasion.RespawnInvasionIntel;
import exerelin.campaign.intel.raid.BaseStrikeIntel;
import exerelin.campaign.intel.raid.RemnantRaidIntel;
import exerelin.campaign.intel.rebellion.RebellionIntel;
import exerelin.campaign.intel.satbomb.SatBombIntel;
import exerelin.utilities.NexConfig;
import exerelin.utilities.NexFactionConfig;
import exerelin.utilities.NexUtils;
import exerelin.utilities.NexUtilsFaction;
import exerelin.utilities.NexUtilsFleet;
import exerelin.utilities.NexUtilsMarket;
import exerelin.utilities.NexUtilsMath;
import exerelin.utilities.StringHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

/**
 * Handles invasion, raid and base strike fleets generation.
 * Originally derived from Dark.Revenant's II_WarFleetManager.
 * 
 * How it works: Every ingame day, each faction accumulates "invasion points"
 * (primarily based on its markets). When it has enough points, it attempts to launch
 * an invasion or raid against one of its enemies.
 * The relevant code paths start in {@code advance()}, so look there.
 */
public class InvasionFleetManager extends BaseCampaignEventListener implements EveryFrameScript
{
	public static final String MANAGER_MAP_KEY = "exerelin_invasionFleetManager";
	
	public static final int MIN_MARINE_STOCKPILE_FOR_INVASION = 200;
	public static final float MAX_MARINE_STOCKPILE_TO_DEPLOY = 0.5f;
	public static final float DEFENDER_STRENGTH_FP_MULT = 0.75f;
	public static final float DEFENDER_STRENGTH_MARINE_MULT = 1;
	public static final float RESPAWN_FLEET_SPAWN_DISTANCE = 18000f;
	// higher = factions (who aren't otherwise at war) invade pirates less often
	public static final float ALL_AGAINST_ONE_INVASION_POINT_MOD = 0.27f;
	// higher = factions are less likely to target pirates (does nothing if pirates are their only enemies)
	public static final float ONE_AGAINST_ALL_INVASION_BE_TARGETED_MOD = 0.35f;
	// pirates get this multiplier bonus to their invasion point growth the more enemies they have
	public static final float ONE_AGAINST_ALL_INVASION_POINT_MOD = 0.215f;
	public static final float HARD_MODE_INVASION_TARGETING_CHANCE = 1.5f;
	public static final int MAX_SIMULTANEOUS_EVENTS_PER_SYSTEM = 3;
	public static final float TEMPLAR_INVASION_POINT_MULT = 1.25f;
	public static final float TEMPLAR_COUNTER_INVASION_FLEET_MULT = 1.25f;
	public static final float PLAYER_AUTONOMOUS_POINT_MULT = 0.25f;
	public static final float PATROL_ESTIMATION_MULT = 0.7f;
	public static final float DEFENCE_ESTIMATION_MULT = 0.75f;
	public static final float STATION_OFFICER_STRENGTH_MULT = 0.25f;
	public static final float BASE_INVASION_COST = 500f;	// for reference, Jangala at start of game is around 500
	public static final float MAX_INVASION_SIZE = 2000;
	public static final float MAX_INVASION_SIZE_ECONOMY_MULT = 6f;
	public static final float SAT_BOMB_CHANCE = 0.4f;
	public static final boolean USE_MARKET_FLEET_SIZE_MULT = false;
	public static final float GENERAL_SIZE_MULT = USE_MARKET_FLEET_SIZE_MULT ? 0.65f : 0.9f;
	public static final float RAID_SIZE_MULT = 0.85f;
	public static final float RESPAWN_SIZE_MULT = 1.2f;
	
	public static final float TANKER_FP_PER_FLEET_FP_PER_10K_DIST = 0.25f;
	public static final Set<String> EXCEPTION_LIST = new HashSet<>(Arrays.asList(new String[]{"templars"}));	// Templars have their own handling
	
	public static final int MAX_ONGOING_INTEL = 10;
	
	public static Logger log = Global.getLogger(InvasionFleetManager.class);
	
	protected final List<OffensiveFleetIntel> activeIntel = new LinkedList();
	protected HashMap<String, Float> spawnCounter = new HashMap<>();
	protected HashMap<String, Boolean> nextIsRaid = new HashMap<>();
	protected HashMap<String, Float> pirateRage = new HashMap<>();
	
	protected final IntervalUtil tracker;
	protected IntervalUtil remnantRaidInterval = new IntervalUtil(300, 390);
	
	protected float daysElapsed = 0;
	protected float templarInvasionPoints = 0;
	protected float templarCounterInvasionPoints = 0;
	protected int numRemnantRaids = 0;
	protected int lifetimeInvasions = 0;
	protected int lifetimeRaids = 0;
	protected float fleetRequestStock;
	protected int fleetRequestCapacity;
	
	public InvasionFleetManager()
	{
		super(true);
		this.tracker = new IntervalUtil(1, 1);
	}
	
	protected Object readResolve() {
		return this;
	}	
	
	protected static String getString(String id)
	{
		return StringHelper.getString("exerelin_fleets", id);
	}
	
	public static String getFleetName(String fleetType, String factionId, float fp)
	{
		String name = "Fleet";
		NexFactionConfig factionConfig = NexConfig.getFactionConfig(factionId);
		
		switch (fleetType) {
			case "exerelinInvasionFleet":
			case "exerelinRespawnFleet":
				name = factionConfig.invasionFleetName;
				if (fp <= 90) name = getString("invasionFleetPrefixSmall") + " " + name;
				else if (fp >= 270) name = getString("invasionFleetPrefixLarge") + " " + name;
				break;
			case "exerelinInvasionSupportFleet":
			case "nex_satBombFleet":
				name = factionConfig.invasionSupportFleetName;
				if (fp <= 75) name = getString("invasionSupportFleetPrefixSmall") + " " + name;
				else if (fp >= 225) name = getString("invasionSupportFleetPrefixLarge") + " " + name;
				break;
			case "nex_defenseFleet":
				name = factionConfig.defenceFleetName;
				if (fp <= 75) name = getString("defenceFleetPrefixSmall") + " " + name;
				else if (fp >= 225) name = getString("defenceFleetPrefixLarge") + " " + name;
				break;
			case "nex_suppressionFleet":
				name = factionConfig.suppressionFleetName;
				if (fp <= 75) name = getString("suppressionFleetPrefixSmall") + " " + name;
				else if (fp >= 225) name = getString("suppressionFleetPrefixLarge") + " " + name;
				break;
		}
		
		return name;
	}
	
	// update this whenever the one in MilitaryBase is changed
	public static int getPatrolCombatFP(PatrolType type) {
		float combat = 0;
		switch (type) {
		case FAST:
			combat = Math.round(3f + (float) 0.5f * 2f) * 5f;
			break;
		case COMBAT:
			combat = Math.round(6f + (float) 0.5f * 3f) * 5f;
			break;
		case HEAVY:
			combat = Math.round(10f + (float) 0.5f * 5f) * 5f;
			break;
		}
		return (int) Math.round(combat);
	}
	
	/**
	 * Estimates the patrol strength of the specified market.
	 * @param market
	 * @param variability Used to multiply the estimated strength by a random Gaussian value.
	 * @return
	 */
	public static float estimatePatrolStrength(MarketAPI market, float variability) {
		Random random = new Random();
		float strength = 10 * market.getSize();
		
		int maxLight = (int) market.getStats().getDynamic().getMod(Stats.PATROL_NUM_LIGHT_MOD).computeEffective(0);
		int maxMedium = (int) market.getStats().getDynamic().getMod(Stats.PATROL_NUM_MEDIUM_MOD).computeEffective(0);
		int maxHeavy = (int) market.getStats().getDynamic().getMod(Stats.PATROL_NUM_HEAVY_MOD).computeEffective(0);
		
		strength += maxLight * getPatrolCombatFP(PatrolType.FAST);
		strength += maxMedium *getPatrolCombatFP(PatrolType.COMBAT);
		strength += maxHeavy * getPatrolCombatFP(PatrolType.HEAVY);
		
		// underestimate large fleet size mults, overestimate small ones
		float fleetSizeMult = market.getStats().getDynamic().getMod(Stats.COMBAT_FLEET_SIZE_MULT).computeEffective(0f);
		//log.info("  True fleet size mult: " + fleetSizeMult);
		fleetSizeMult = 1 + (fleetSizeMult - 1) * 0.75f;
		//log.info("  Estimated fleet size mult: " + fleetSizeMult);
		
		strength *= fleetSizeMult;
		
		if (variability > 0) {
			float gauss = NexUtils.getBoundedGaussian(random, -3, 3);
			
			strength *= 1 + gauss * variability;
		}
			
		
		strength *= PATROL_ESTIMATION_MULT;
		
		return strength;
	}
	
	/**
	 * Estimates the patrol strength the specified attacker would face in this star system.
	 * @param attacker
	 * @param targetFaction If non-null, only count markets of that faction, 
	 * else count markets of all factions hostile to the attacker.
	 * @param system
	 * @param variability Used to multiply the estimated strength by a random Gaussian value.
	 * @return
	 */
	public static float estimatePatrolStrength(FactionAPI attacker, FactionAPI targetFaction, 
			StarSystemAPI system, float variability) {
		float strength = 0f;
		
		if (system == null) return 0;
		
		for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system))
		{
			if (targetFaction != null) {
				if (market.getFaction() != targetFaction) continue;
			}
			else {
				if (!market.getFaction().isHostileTo(attacker))
					continue;
			}
			
			strength += InvasionFleetManager.estimatePatrolStrength(market, variability);
		}
		
		return strength;
	}
	
	public static float estimateStationStrength(MarketAPI market) {
		CampaignFleetAPI station = Misc.getStationFleet(market);
		float stationStr = 0;
		if (station != null) {
			stationStr = NexUtilsFleet.getFleetStrength(station, true, true, false);
			float officerStr = NexUtilsFleet.getFleetStrength(station, true, true, true) - stationStr;
			stationStr += officerStr * STATION_OFFICER_STRENGTH_MULT;
			stationStr *= 0.5f;
		}
		return stationStr;
	}
	
	public static float getFactionDoctrineFleetSizeMult(FactionAPI faction) {
		return 1 + (faction.getDoctrine().getNumShips() - 1) * 0.25f;
	}
	
	public static float getWantedFleetSize(FactionAPI attacker, MarketAPI target,
			float variability, boolean countAllHostile) {
		return getWantedFleetSize(attacker, target, variability, countAllHostile, 1);
	}
	
	/**
	 * Get the desired scaling fleet size for the specified attacker and target.
	 * @param attacker
	 * @param target
	 * @param variability Used to multiply the estimated strength by a random Gaussian value.
	 * @param countAllHostile Count patrols only from markets belonging to the 
	 * target faction, or all that are hostile to the attacker?
	 * @param maxMult Multiplier for maximum fleet size.
	 * @return
	 */
	public static float getWantedFleetSize(FactionAPI attacker, MarketAPI target,
			float variability, boolean countAllHostile, float maxMult)
	{
		FactionAPI targetFaction = target.getFaction();
		StarSystemAPI system = target.getStarSystem();
		
		float defenderStr = estimatePatrolStrength(attacker, 
				countAllHostile ? null : targetFaction, 
				system, variability);
		//log.info("\tPatrol strength: " + defenderStr);
		
		float stationStr = estimateStationStrength(target);		
		//log.info("\tStation strength: " + stationStr);
		
		float defensiveStr = defenderStr + stationStr;
		defensiveStr *= DEFENCE_ESTIMATION_MULT;
		//log.info("\tModified total defense strength: " + defensiveStr);
		
		float strFromSize = target.getSize() * target.getSize() * 3;
		//log.info("\tMarket size modifier: " + strFromSize);
		defensiveStr += strFromSize;
		
		defensiveStr *= GENERAL_SIZE_MULT;
		
		float max = getMaxInvasionSize(attacker.getId(), maxMult);
		if (defensiveStr > max)
			defensiveStr = max;
		
		log.info("\tWanted fleet size vs. " + target.getName() + ": " + defensiveStr);
		return Math.max(defensiveStr, 30);
	}
	
	// retained for comparison purposes
	@Deprecated
	public static float getWantedFleetSizeOld(FactionAPI attacker, MarketAPI target,
			float variability, boolean countAllHostile)
	{
		FactionAPI targetFaction = target.getFaction();
		StarSystemAPI system = target.getStarSystem();
		
		float defenderStr = estimatePatrolStrength(attacker, 
				countAllHostile ? null : targetFaction, 
				system, variability);
		log.info("\tPatrol strength: " + defenderStr);
		
		float stationStr = 0;
		CampaignFleetAPI station = Misc.getStationFleet(target);
		if (station != null) {
			stationStr = station.getFleetData().getEffectiveStrength();
			stationStr *= 0.5f;
		}
		
		log.info("\tStation strength: " + stationStr);
		
		float defensiveStr = defenderStr + stationStr;
		
		defensiveStr *= GENERAL_SIZE_MULT;
		
		if (defensiveStr > MAX_INVASION_SIZE)
			defensiveStr = MAX_INVASION_SIZE;
		
		log.info("\tWanted fleet size: " + defensiveStr);
		return Math.max(defensiveStr, 30);
	}
	
	public static void debugFleetSizes() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			log.info("Testing old inv. size for market " + market.getName());
			float old = getWantedFleetSizeOld(null, market, 0, false);
			
			log.info("Testing new inv. size for market " + market.getName());
			float nu = getWantedFleetSize(null, market, 0, false);
			
			log.info("Difference: " + (nu - old));
		}
	}
	
	public static float getOrganizeTime(float fp) {
		return 10 + fp/30;
	}
	
	public static float getInvasionSizeMult(String factionId) {
		float mult = 1 + NexConfig.getFactionConfig(factionId).invasionFleetSizeMod;
		mult *= NexConfig.invasionFleetSizeMult;
		return mult;
	}
	
	/**
	 * Gets the weighting of the specified market in picking where to launch an invasion or raid from.
	 * @param market
	 * @return
	 */
	public static float getMarketWeightForInvasionSource(MarketAPI market) {
		//marineStockpile = market.getCommodityData(Commodities.MARINES).getAverageStockpileAfterDemand();
		//if (marineStockpile < MIN_MARINE_STOCKPILE_FOR_INVASION)
		//		continue;
		float weight = 1;	 //marineStockpile;
		if (market.hasIndustry(Industries.PATROLHQ)) {
			weight *= 1.2f;
		}
		if (market.hasIndustry(Industries.MILITARYBASE)) {
			weight *= 1.5f;
		}
		if (market.hasIndustry(Industries.HIGHCOMMAND)) {
			weight *= 2;
		}
		if (market.hasIndustry(Industries.MEGAPORT)) {
			weight *= 1.5f;
		}
		if (market.hasIndustry(Industries.HEAVYINDUSTRY)) {
			weight *= 1.2f;
		}
		if (market.hasIndustry(Industries.ORBITALWORKS)) {
			weight *= 1.5f;
		}
		if (market.hasIndustry(Industries.WAYSTATION)) {
			weight *= 1.2f;
		}
		weight *= 0.5f + (0.5f * market.getSize() * market.getStabilityValue());
		
		return weight;
	}
	
	public static boolean canSatBomb(FactionAPI attacker, FactionAPI defender) {
		if (attacker.getRelationshipLevel(defender) != RepLevel.VENGEFUL)
			return false;
		
		if (attacker.getCustom() == null || !attacker.getCustom().has(Factions.CUSTOM_PUNITIVE_EXPEDITION_DATA))
			return false;
		
		boolean canBombard = false;
		if (attacker.getCustom() != null && attacker.getCustom().has(Factions.CUSTOM_PUNITIVE_EXPEDITION_DATA))
		{
			canBombard = attacker.getCustom().optJSONObject(Factions.CUSTOM_PUNITIVE_EXPEDITION_DATA)
				.optBoolean("canBombard", false);
		}
		
		if (defender.isPlayerFaction() || PlayerFactionStore.getPlayerFaction() == defender) 
		{
			canBombard = canBombard || StatsTracker.getStatsTracker().getMarketsSatBombarded() > 0;
		}
		
		return canBombard;
	}
	
	public static boolean canSatBomb(FactionAPI attacker, MarketAPI target) 
	{
		String origOwner = NexUtilsMarket.getOriginalOwner(target);
		if (origOwner != null && !attacker.isHostileTo(origOwner))
			return false;
		
		return canSatBomb(attacker, target.getFaction());
	}
	
	public void modifySpawnCounter(String factionId, float amount) {
		NexUtils.modifyMapEntry(spawnCounter, factionId, amount);
	}
	
	public OffensiveFleetIntel generateInvasionOrRaidFleet(FactionAPI faction, FactionAPI targetFaction, EventType type)
	{
		return generateInvasionOrRaidFleet(faction, targetFaction, type, 1);
	}
	
	public MarketAPI getSourceMarketForFleet(FactionAPI faction, List<MarketAPI> markets) {
		WeightedRandomPicker<MarketAPI> sourcePicker = new WeightedRandomPicker();
		for (MarketAPI market : markets) {
			if (market.getFaction() != faction) continue;
			if (market.isHidden()) continue;
			if (market.hasCondition(Conditions.ABANDONED_STATION)) continue;
			if (market.getPrimaryEntity() instanceof CampaignFleetAPI) continue;
			if (!NexUtilsMarket.hasWorkingSpaceport(market)) continue;
			if (market.getSize() < 3) continue;
			// markets with ongoing rebellions can't launch invasions
			if (RebellionIntel.isOngoing(market))
				continue;
			
			// handling for invasions by player autonomous colonies
			if (market.getFaction().isPlayerFaction() && !NexConfig.followersInvasions && market.isPlayerOwned())
			{
				continue;
			}
			
			sourcePicker.add(market, getMarketWeightForInvasionSource(market));
		}
		MarketAPI originMarket = sourcePicker.pick();
		return originMarket;
	}
	
	// TODO
	protected boolean areTooManyOngoing(MarketAPI market) {
		return false;
	}
	
	public MarketAPI getTargetMarketForFleet(FactionAPI faction, FactionAPI targetFaction, 
			Vector2f originLoc, List<MarketAPI> markets, EventType type) {
		return getTargetMarketForFleet(faction, targetFaction, originLoc,
				markets, type, false);
	}
	
	public MarketAPI getTargetMarketForFleet(FactionAPI faction, FactionAPI targetFaction, 
			Vector2f originLoc, List<MarketAPI> markets, EventType type, boolean isRemnantRaid) 
	{
		String factionId = faction.getId();
		WeightedRandomPicker<MarketAPI> targetPicker = new WeightedRandomPicker();
		Set<LocationAPI> systemsWeHavePresenceIn = NexUtilsFaction.getLocationsWithFactionPresence(factionId);
		
		for (MarketAPI market : markets) 
		{
			// likely to crash in 0.9.1a
			if (market.getContainingLocation().isHyperspace())
				continue;
			
			FactionAPI marketFaction = market.getFaction();
			String marketFactionId = marketFaction.getId();
			
			if (EXCEPTION_LIST.contains(marketFactionId) && targetFaction != marketFaction) continue;
			if (targetFaction != null && targetFaction != marketFaction)
				continue;
			
			if (!marketFaction.isHostileTo(faction)) continue;
			
			if (!isRemnantRaid && !NexUtilsMarket.shouldTargetForInvasions(market, 0)) continue;
			
			if (type == EventType.SAT_BOMB && faction.getId().equals(NexUtilsMarket.getOriginalOwner(market)))
				continue;
			
			if (isRemnantRaid) {
				// non-hard mode mercy for new player colonies
				// TODO: replace with an expiring memory key when we get colonization listener
				if (!SectorManager.getManager().isHardMode() && marketFaction.isPlayerFaction() && market.getSize() < 4)
					continue;
			}
			
			
			/*
			float defenderStrength = InvasionRound.GetDefenderStrength(market);
			float estimateMarinesRequired = defenderStrength * 1.2f;
			if (estimateMarinesRequired > marineStockpile * MAX_MARINE_STOCKPILE_TO_DEPLOY)
				continue;	 // too strong for us
			*/
			
			// Tiandong can't invade Point Mogui
			if (factionId.equals("tiandong") && market.getId().equals("tiandong_mogui_market"))
				continue;
			
			boolean isPirateFaction = NexUtilsFaction.isPirateFaction(factionId);
			if (factionId.equals(Factions.PLAYER))
				isPirateFaction = isPirateFaction || NexUtilsFaction.isPirateFaction(
						PlayerFactionStore.getPlayerFactionId());
			
			float weight = 1;
			
			weight *= getLowProfileMult(marketFactionId);
			
			// base weight based on distance
			if (originLoc != null) {
				float dist = Misc.getDistance(market.getLocationInHyperspace(), originLoc);
				if (dist < 5000.0F) {
					dist = 5000.0F;
				}
				weight = 20000.0F / dist;
			}
			
			// modifier for defense strength (prefer weaker targets, proportional to size)
			float defStr = getWantedFleetSize(faction, market, 1.1f, false);
			weight *= 10000/defStr;
			weight *= market.getSize();
			
			//weight *= market.getSize() * market.getStabilityValue();	// try to go after high value targets
			if (NexUtilsFaction.isFactionHostileToAll(marketFactionId) || isPirateFaction)
				weight *= ONE_AGAINST_ALL_INVASION_BE_TARGETED_MOD;
			
			boolean haveHeavyIndustry = NexUtilsMarket.hasHeavyIndustry(market);
			boolean revanchist = NexUtilsMarket.wasOriginalOwner(market, factionId)
					&& type == EventType.INVASION;
			
			// revanchism, prioritise heavy industry
			if (haveHeavyIndustry) {
				if (revanchist) {
					weight *= 80f;
				}
				else {
					weight *= 5f;
				}
			} else if (revanchist) {
				weight *= 5f;
			}
			
			// focus on cleaning up systems we already have
			if (systemsWeHavePresenceIn.contains(market.getContainingLocation())) 
			{
				weight *= 10f;
			}	

			// defender of the faith
			if (market.hasCondition(Conditions.LUDDIC_MAJORITY) && NexUtilsFaction.isLuddicFaction(factionId))
				weight *= 4;
			
			// Remnants "rescue" their friends
			if (faction.getId().equals(Factions.REMNANTS)) {
				weight *= 1 + HegemonyInspectionManager.getAICoreUseValue(market)/5;
				if (market.hasCondition(Conditions.ROGUE_AI_CORE)) weight *= 3;
			}
			
			// hard mode
			if (SectorManager.getManager().isHardMode())
			{
				if (marketFactionId.equals(PlayerFactionStore.getPlayerFactionId()) 
						|| marketFactionId.equals(Factions.PLAYER))
					weight *= HARD_MODE_INVASION_TARGETING_CHANCE;
			}

			// help ongoing rebellions
			RebellionIntel rebel = RebellionIntel.getOngoingEvent(market);
			if (rebel != null && !faction.isHostileTo(rebel.getRebelFaction()))
				weight *= 5;

			targetPicker.add(market, weight);
			
		}
		MarketAPI targetMarket = targetPicker.pick();
		return targetMarket;
	}
	
	/**
	 * Try to create an invasion fleet or raid fleet.
	 * @param faction The faction launching an invasion
	 * @param targetFaction
	 * @param type
	 * @param sizeMult
	 * @return The invasion fleet intel, if one was created
	 */
	public OffensiveFleetIntel generateInvasionOrRaidFleet(FactionAPI faction, FactionAPI targetFaction, 
			EventType type, float sizeMult)
	{
		SectorAPI sector = Global.getSector();
		List<MarketAPI> markets = sector.getEconomy().getMarketsCopy();
		
		MarketAPI originMarket = getSourceMarketForFleet(faction, markets);
		if (originMarket == null) {
			return null;
		}
		//log.info("\tStaging from " + originMarket.getName());
		//marineStockpile = originMarket.getCommodityData(Commodities.MARINES).getAverageStockpileAfterDemand();

		// now we pick a target
		MarketAPI targetMarket = getTargetMarketForFleet(faction, targetFaction, 
				originMarket.getLocationInHyperspace(), markets, type);
		if (targetMarket == null) {
			return null;
		}
		//log.info("\tTarget: " + targetMarket.getName());
		
		// sat bomb
		// note: don't sat bomb own originally-owned markets
		if (NexConfig.allowNPCSatBomb && type == EventType.RAID && Math.random() < SAT_BOMB_CHANCE 
				&& canSatBomb(faction, targetMarket))
		{
			type = EventType.SAT_BOMB;
		}
		
		// always invade rather than raid derelicts
		if (targetMarket.getFactionId().equals("nex_derelict") 
				&& (type == EventType.RAID || type == EventType.SAT_BOMB))
			type = EventType.INVASION;
		
		return generateInvasionOrRaidFleet(originMarket, targetMarket, type, sizeMult);
	}
	
	public OffensiveFleetIntel generateInvasionOrRaidFleet(MarketAPI origin, MarketAPI target, 
			EventType type, float sizeMult) {
		FactionAPI faction = origin.getFaction();
		String factionId = faction.getId();
		float maxMult = type == EventType.RESPAWN ? 5 : 1;
		
		float fp = getWantedFleetSize(faction, target, 0.1f, false, maxMult);
		float organizeTime = getOrganizeTime(fp);
		fp *= InvasionFleetManager.getInvasionSizeMult(factionId);
		fp *= sizeMult;
		if (type == EventType.RAID)
			fp *= RAID_SIZE_MULT;
		else if (type == EventType.RESPAWN)
			fp *= RESPAWN_SIZE_MULT;
		
		if (type != EventType.RAID)
			organizeTime *= 1.25f;
		
		// okay, assemble battlegroup
		switch (type) {
			case RESPAWN:
			{
				log.info("Spawning respawn fleet for " + faction.getDisplayName() + "; source " + origin.getName() + "; target " + target.getName());
				RespawnInvasionIntel intel = new RespawnInvasionIntel(faction, origin, target, fp, organizeTime);
				intel.init();
				activeIntel.add(intel);
				return intel;
			}
			case INVASION:
			{
				log.info("Spawning invasion fleet for " + faction.getDisplayName() + "; source " + origin.getName() + "; target " + target.getName());
				InvasionIntel intel = new InvasionIntel(faction, origin, target, fp, organizeTime);
				intel.init();
				activeIntel.add(intel);
				return intel;
			}
			case RAID:
			{
				log.info("Spawning raid fleet for " + faction.getDisplayName() + "; source " + origin.getName() + "; target " + target.getName());
				NexRaidIntel intel = new NexRaidIntel(faction, origin, target, fp, organizeTime);
				intel.init();
				activeIntel.add(intel);
				return intel;
			}
			case BASE_STRIKE:
			{
				log.info("Spawning base strike fleet for " + faction.getDisplayName() + "; source " + origin.getName() + "; target " + target.getName());
				BaseStrikeIntel intel = new BaseStrikeIntel(faction, origin, target, fp, organizeTime);
				intel.init();
				return intel;
			}
			case SAT_BOMB:
			{
				log.info("Spawning saturation bombardment fleet for " + faction.getDisplayName() + "; source " + origin.getName() + "; target " + target.getName());
				SatBombIntel intel = new SatBombIntel(faction, origin, target, fp, organizeTime);
				intel.init();
				activeIntel.add(intel);
				return intel;
			}
		}
		return null;
	}
	
	/**
	 * Gets the value of the specified commodity availability on the specified 
	 * market in contributing to invasion points. Imported commodities are worth less,
	 * locally manufactured commodities worth more.
	 * @param market
	 * @param commodity
	 * @return
	 */
	protected static float getCommodityPoints(MarketAPI market, String commodity) {
		float pts = market.getCommodityData(commodity).getAvailable();
		CommoditySourceType source = market.getCommodityData(commodity).getCommodityMarketData().getMarketShareData(market).getSource();
		if (source == CommoditySourceType.GLOBAL)
			pts *= 0.75f;
		else if (source == CommoditySourceType.LOCAL)
			pts *= 5;
		
		return pts;
	}
	
	/**
	 * Gets the contribution of the specified market to invasion points, based on its commodity availability.
	 * @param market
	 * @return
	 */
	public static float getMarketInvasionCommodityValue(MarketAPI market) {
		float ships = getCommodityPoints(market, Commodities.SHIPS);
		float supplies = getCommodityPoints(market, Commodities.SUPPLIES);
		float marines = getCommodityPoints(market, Commodities.MARINES);
		float mechs = getCommodityPoints(market, Commodities.HAND_WEAPONS);
		float fuel = getCommodityPoints(market, Commodities.FUEL);
		
		float stabilityMult = 0.25f + (0.75f * market.getStabilityValue()/10);
		
		float total = (ships*2 + supplies + marines + mechs + fuel) * stabilityMult;
		
		return total;
	}
	
	protected static float getPointsPerMarketPerTick(MarketAPI market)
	{
		return getMarketInvasionCommodityValue(market) * NexConfig.invasionPointEconomyMult;
	}
	
	// runcode Console.showMessage("" + exerelin.campaign.fleets.InvasionFleetManager.getMaxInvasionSize("hegemony"), 1);
	/**
	 * Gets the max invasion size for the specified attacking faction, based on their economy.
	 * Capped at {@code MAX_INVASION_SIZE}, and returns that value immediately if brawl mode is enabled.
	 * @param factionId
	 * @param maxMult Multiplier for maximum economy-based size.
	 * @return
	 */
	public static float getMaxInvasionSize(String factionId, float maxMult) {
		if (Global.getSettings().getBoolean("nex_brawlMode")) {
			return MAX_INVASION_SIZE;
		}
		
		float value = 0;
		List<MarketAPI> markets = NexUtilsFaction.getFactionMarkets(factionId);
		for (MarketAPI market : markets) {
			value += getMarketInvasionCommodityValue(market);
		}
		value *= MAX_INVASION_SIZE_ECONOMY_MULT * maxMult;
		
		if (value > MAX_INVASION_SIZE)
			value = MAX_INVASION_SIZE;
		
		return value;
	}
	
	/**
	 * Does the faction want to launch a raid instead of an invasion right now?
	 * @param factionId
	 * @return
	 */
	public boolean shouldRaid(String factionId) {
		if (!NexConfig.enableInvasions) return true;
		if (!NexConfig.getFactionConfig(factionId).canInvade) return true;
		if (!nextIsRaid.containsKey(factionId))
			nextIsRaid.put(factionId, Math.random() > 0.5f);
		return nextIsRaid.get(factionId);
	}
	
	/**
	 * Generates invasion points for all applicable markets and factions,
	 * and attempts to generate invasion/raid fleets if the required number
	 * of points is reached.
	 */
	protected void processInvasionPoints()
	{
		SectorAPI sector = Global.getSector();
		List<MarketAPI> markets = sector.getEconomy().getMarketsCopy();
		//float marineStockpile = 0;
		//log.info("Starting invasion fleet check");
		
		// slow down invasion point accumulation when there are already a bunch of invasions active
		float ongoingMod = (float)activeIntel.size() / MAX_ONGOING_INTEL;
		if (ongoingMod >= 1) return;
		//log.info("Ongoing invasion point mod: " + (- 0.75f * ongoingMod) + " (" + activeIntel.size() + ")");
		ongoingMod = 1 - 0.75f * ongoingMod;
		
		boolean allowPirates = NexConfig.allowPirateInvasions;
		
		// increment points by market
		if (spawnCounter == null) spawnCounter = new HashMap<>();
		HashMap<String, Float> pointsPerFaction = new HashMap<>();
		for (MarketAPI market : markets)
		{
			String factionId = market.getFactionId();
			if (EXCEPTION_LIST.contains(factionId)) continue;
			
			if (market.isHidden()) continue;
			
			float mult = 1;
			
			if (factionId.equals(Factions.PLAYER) && !NexConfig.followersInvasions) {
				if (market.isPlayerOwned()) {
					continue;
				}
				else mult *= PLAYER_AUTONOMOUS_POINT_MULT;
			}
			
			if (!pointsPerFaction.containsKey(factionId))
				pointsPerFaction.put(factionId, 0f);
			
			float currPoints = pointsPerFaction.get(factionId);
			float addedPoints = getPointsPerMarketPerTick(market) * mult;
			
			currPoints += addedPoints;
			pointsPerFaction.put(factionId, currPoints);
		}
		
		int playerLevel = Global.getSector().getPlayerPerson().getStats().getLevel();
		
		// pick a faction to invade someone
		List<String> liveFactionIds = SectorManager.getLiveFactionIdsCopy();
		for (String factionId: liveFactionIds)
		{
			if (EXCEPTION_LIST.contains(factionId)) continue;
			FactionAPI faction = sector.getFaction(factionId);
			if (faction.isNeutralFaction()) continue;
			NexFactionConfig config = NexConfig.getFactionConfig(factionId);
			if (!config.playableFaction) continue;
			
			boolean isPirateFaction = NexUtilsFaction.isPirateFaction(factionId);
			if (factionId.equals(Factions.PLAYER))
				isPirateFaction = isPirateFaction || NexUtilsFaction.isPirateFaction(
						PlayerFactionStore.getPlayerFactionId());
			if (!allowPirates && isPirateFaction) continue;
			
			float mult = 0f;
			List<String> enemies = DiplomacyManager.getFactionsAtWarWithFaction(faction, allowPirates, false, false);
			if (enemies.isEmpty()) continue;
			
			if (NexUtilsFaction.isFactionHostileToAll(factionId))
			{
				float numWars = enemies.size();
				numWars = (float)Math.sqrt(numWars);
				mult = numWars*ONE_AGAINST_ALL_INVASION_POINT_MOD + (1 - ONE_AGAINST_ALL_INVASION_POINT_MOD);
			}
			else
			{
				for (String enemyId : enemies)
				{
					if (EXCEPTION_LIST.contains(factionId)) continue;
					if (NexUtilsFaction.isFactionHostileToAll(enemyId))
					{
						float enemyWars = DiplomacyManager.getFactionsAtWarWithFaction(enemyId, 
								allowPirates, true, false).size();
						enemyWars = (float)Math.sqrt(enemyWars);
						if (enemyWars > 0 )
							mult += 1/((enemyWars*ALL_AGAINST_ONE_INVASION_POINT_MOD) + (1));
					}
					else 
					{
						mult = 1;
						break;
					}
				}
				if (mult > 1) mult = 1;
			}
			
			// increment invasion counter for faction
			
			// safety (faction can be live without markets if its last market decivilizes)
			if (!pointsPerFaction.containsKey(factionId))
				pointsPerFaction.put(factionId, 0f);
			
			float counter = getSpawnCounter(factionId);
			float increment = pointsPerFaction.get(factionId);
			if (!faction.isPlayerFaction() || NexConfig.followersInvasions) {
				increment += NexConfig.baseInvasionPointsPerFaction;
				increment += NexConfig.invasionPointsPerPlayerLevel * playerLevel;
			}
			
			increment *= mult * MathUtils.getRandomNumberInRange(0.75f, 1.25f);
			increment *= config.invasionPointMult;
			increment *= ongoingMod;
			
			counter += increment;
			
			float pointsRequired = NexConfig.pointsRequiredForInvasionFleet;
			if (counter < pointsRequired)
			{
				spawnCounter.put(factionId, counter);
				//if (counter > pointsRequired/2 && oldCounter < pointsRequired/2)
				//	generateInvasionOrRaidFleet(faction, null, true);	 // launch a raid
			}
			else
			{
				// okay, we can invade or raid
				// invasions and raids alternate
				boolean shouldRaid = shouldRaid(factionId);
				OffensiveFleetIntel intel = generateInvasionOrRaidFleet(faction, null, 
						shouldRaid ? EventType.RAID : EventType.INVASION);
				if (intel != null)
				{
					counter -= getInvasionPointReduction(pointsRequired, intel);
					if (shouldRaid) lifetimeRaids++;
					else lifetimeInvasions++;
					spawnCounter.put(factionId, counter);
					nextIsRaid.put(factionId, !shouldRaid);
				}
			}
		}
	}
	
	protected void processTemplarInvasionPoints()
	{
		List<String> liveFactionIds = SectorManager.getLiveFactionIdsCopy();
		if (!liveFactionIds.contains("templars")) return;
		
		List<String> enemies = DiplomacyManager.getFactionsAtWarWithFaction("templars", 
				NexConfig.allowPirateInvasions, false, false);
		if (enemies.isEmpty()) return;
		float templarDominance = DiplomacyManager.getDominanceFactor("templars");
		float perLevelPoints = Global.getSector().getPlayerPerson().getStats().getLevel() * NexConfig.invasionPointsPerPlayerLevel;
		
		// Templars invading and raiding others
		templarInvasionPoints += (100 + perLevelPoints) 
			* NexConfig.getFactionConfig("templars").invasionPointMult * TEMPLAR_INVASION_POINT_MULT;
		
		float req = NexConfig.pointsRequiredForInvasionFleet;
		boolean shouldRaid = shouldRaid("templars");
		EventType type = shouldRaid ? EventType.RAID : EventType.INVASION;
		if (templarInvasionPoints >= req)
		{
			OffensiveFleetIntel intel = generateInvasionOrRaidFleet(Global.getSector().getFaction("templars"), null, 
					type);
			if (intel != null) {
				templarInvasionPoints -= getInvasionPointReduction(req, intel);
				nextIsRaid.put("templars", !shouldRaid);
			}
			//Global.getSector().getCampaignUI().addMessage("Launching Templar invasion fleet");
		}
		
		// Others invading and raiding Templars
		if (!Global.getSettings().getBoolean("nex_invade_and_raid_templars")) return;
		templarCounterInvasionPoints += (100 + 200 * templarDominance + perLevelPoints) * TEMPLAR_INVASION_POINT_MULT;
		if (templarCounterInvasionPoints >= req)
		{
			WeightedRandomPicker<String> picker = new WeightedRandomPicker();
			for (String factionId : enemies)
			{
				if (factionId.equals(Factions.PLAYER) && !NexConfig.followersInvasions)
					continue;
				picker.add(factionId, NexUtilsFaction.getFactionMarketSizeSum(factionId));
			}
			String factionId = picker.pick();
			FactionAPI faction = Global.getSector().getFaction(factionId);
			shouldRaid = shouldRaid(factionId);
			type = shouldRaid ? EventType.RAID : EventType.INVASION;
			
			OffensiveFleetIntel intel = generateInvasionOrRaidFleet(faction, Global.getSector().getFaction("templars"), 
					type, TEMPLAR_COUNTER_INVASION_FLEET_MULT);
			//Global.getSector().getCampaignUI().addMessage("Launching counter-Templar invasion fleet");
			if (intel != null) {
				templarCounterInvasionPoints -= getInvasionPointReduction(req, intel);
				nextIsRaid.put(factionId, !shouldRaid);
			}
		}
	}
	
	protected void spawnBaseStrikeFleet(FactionAPI faction, MarketAPI target) {
		MarketAPI source = getSourceMarketForFleet(faction, Global.getSector().getEconomy().getMarketsCopy());
		if (source == null)
			return;
		
		OffensiveFleetIntel intel = generateInvasionOrRaidFleet(source, target, EventType.BASE_STRIKE, 1);
		
		NexUtils.modifyMapEntry(pirateRage, faction.getId(), -100);
	}
	
	/**
	 * Accumulate the points that lead major factions to launch base strike fleets against pirates and Luddic Path.
	 * Points are generated for each market that has pirate activity or a Pather cell.
	 * When a faction reaches 100 or more points, a strike is launched against the base that pushed it over the limit.
	 */
	protected void processPirateRage() {
		//boolean pirateInvasions = ExerelinConfig.allowPirateInvasions;
		float rageIncrement = Global.getSettings().getFloat("nex_pirateRageIncrement");
		
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getFaction().isPlayerFaction()) continue;
			
			String factionId = market.getFactionId();
			if (Factions.INDEPENDENT.equals(factionId) || "nex_derelict".equals(factionId)) continue;
			
			//if (!pirateInvasions && !ExerelinUtilsFaction.isPirateFaction(market.getFactionId()))
			//	continue;
			
			if (market.hasCondition(Conditions.PIRATE_ACTIVITY) && market.getFaction().isHostileTo(Factions.PIRATES)) {
				MarketConditionAPI cond = market.getCondition(Conditions.PIRATE_ACTIVITY);
				PirateActivity plugin = (PirateActivity)cond.getPlugin();

				float rage = plugin.getIntel().getStabilityPenalty();
				if (rage > 0) {
					rage *= rageIncrement;
					float newVal = NexUtils.modifyMapEntry(pirateRage, factionId, rage);
					//log.info("Incrementing " + rage + " rage from market " + market.getName() + 
					//		" of faction " + market.getFaction().getDisplayName() + ", now " + newVal);
					if (newVal > 100) {
						spawnBaseStrikeFleet(market.getFaction(), plugin.getIntel().getMarket());
					}
				}
			}
			if (market.hasCondition(Conditions.PATHER_CELLS) && market.getFaction().isHostileTo(Factions.LUDDIC_PATH)) {
				MarketConditionAPI cond = market.getCondition(Conditions.PATHER_CELLS);
				LuddicPathCells cellCond = (LuddicPathCells)(cond.getPlugin());
				LuddicPathCellsIntel cellIntel = cellCond.getIntel();
				LuddicPathBaseIntel base;
				
				boolean sleeper = cellIntel.getSleeperTimeout() > 0;
				if (sleeper) continue;
				base = LuddicPathCellsIntel.getClosestBase(market);
				if (base == null) continue;
				
				float rage = 1 * rageIncrement;
				float newVal = NexUtils.modifyMapEntry(pirateRage, factionId, rage);
				if (newVal > 100 && !base.getName().endsWith("Bounty Posted")) {
					spawnBaseStrikeFleet(market.getFaction(), base.getMarket());
				}
			}
		}
	}
	
	/**
	 * Gets the number of invasion points that should be deducted as a result of launching an invasion or raid.
	 * @param base
	 * @param intel
	 * @return
	 */
	public float getInvasionPointReduction(float base, OffensiveFleetIntel intel)
	{
		float amount = base * Math.max(intel.getBaseFP()/BASE_INVASION_COST, 0.8f);
		log.info("Deducting " + amount + " invasion points for " + intel.getName());
		return amount;
	}
	
	// runcode Console.showMessage("" + exerelin.campaign.fleets.InvasionFleetManager.getManager().getSpawnCounter("player"));
	public float getSpawnCounter(String factionId) {
		if (!spawnCounter.containsKey(factionId))
			spawnCounter.put(factionId, 0f);
		return spawnCounter.get(factionId);
	}
	
	/**
	 * Finds a base (i.e. a station fleet) to launch Remnant-type raids from. A market is not required.
	 * @param faction
	 * @return
	 */
	public static CampaignFleetAPI findBase(FactionAPI faction) {
		WeightedRandomPicker<CampaignFleetAPI> basePicker = new WeightedRandomPicker();
		Vector2f center = new Vector2f(0, 0);
		for (StarSystemAPI system : Global.getSector().getStarSystems()) 
		{
			for (CampaignFleetAPI fleet : system.getFleets()) 
			{
				if (fleet.isStationMode() && fleet.getFaction() == faction) 
				{
					float dist = MathUtils.getDistance(fleet.getLocation(), center);
					float weight = 50000/dist;
					if (weight > 20) weight = 20;
					if (weight < 0.1f) weight = 0.1f;
					basePicker.add(fleet, weight);
				}
			}
		}
		CampaignFleetAPI base = basePicker.pick();
		return base;
	}
	
	/**
	 * Try to create a Remnant-style raid fleet. Also works for DME's Blade Breakers.
	 * @param faction The faction launching an invasion
	 * @param targetFaction
	 * @param sizeMult
	 * @return The invasion fleet intel, if one was created
	 */
	public RemnantRaidIntel generateRemnantRaidFleet(FactionAPI faction, FactionAPI targetFaction, float sizeMult)
	{
		if (faction == null) {
			WeightedRandomPicker<FactionAPI> factionPicker = new WeightedRandomPicker<>();
			for (FactionAPI candidate : Global.getSector().getAllFactions()) 
			{
				if (NexConfig.getFactionConfig(candidate.getId()).raidsFromBases)
					factionPicker.add(candidate);
			}
			faction = factionPicker.pick();
		}
		if (faction == null) return null;
		
		SectorAPI sector = Global.getSector();
		List<MarketAPI> markets = sector.getEconomy().getMarketsCopy();
		String factionId = faction.getId();
		
		// pick a source base
		CampaignFleetAPI base = findBase(faction);
		if (base == null)
			return null;
		
		// now we pick a target
		MarketAPI targetMarket = getTargetMarketForFleet(faction, null, base.getLocationInHyperspace(), 
				markets, EventType.RAID, true);
		if (targetMarket == null) {
			return null;
		}
		//log.info("\tTarget: " + targetMarket.getName());
		
		float fp = 500;	//getWantedFleetSize(faction, targetMarket, 0.2f, true) * 2.5f;
		float mult = Math.min(1 + (numRemnantRaids * 0.25f), 3f);
		fp *= mult;
		float organizeTime = getOrganizeTime(fp);
		fp *= 1 + NexConfig.getFactionConfig(factionId).invasionFleetSizeMod;
		
		log.info("Spawning Remnant-style raid fleet for " + faction.getDisplayName() 
				+ " from base in " + base.getContainingLocation() + "; target " + targetMarket.getName());
		RemnantRaidIntel intel = new RemnantRaidIntel(faction, base, targetMarket, fp, organizeTime, numRemnantRaids);
		intel.init();
		numRemnantRaids++;
		return intel;
	}
	
	// FIXME: doesn't count counter-invasions and doesn't track sat bombs
	public int getNumLifetimeInvasions() {
		return lifetimeInvasions;
	}
	
	public int getNumLifetimeRaids() {
		return lifetimeRaids;
	}
		
	/**
	 * Updates the player's fleet request point capacity and increments points.
	 * @param days
	 */
	public void updateFleetRequestStock(float days) {
		int capacity = 0;
		float increment = 0;
		
		List<MarketAPI> requestable = NexUtilsFaction.getFactionMarkets(Factions.PLAYER);
		if (Misc.getCommissionFaction() != null) {
			requestable.addAll(NexUtilsFaction.getFactionMarkets(Misc.getCommissionFactionId()));
		}
		for (MarketAPI market : requestable)
		{
			boolean playerOwned = market.isPlayerOwned();
			float thisIncrement = getMarketInvasionCommodityValue(market)/20;
			if (!playerOwned) thisIncrement *= 0.25f;
			increment += thisIncrement;
			
			float thisCapacity = getFleetRequestCapacity(market);
			if (!playerOwned) thisCapacity *= 0.25f;
			capacity += thisCapacity;
		}
		
		fleetRequestCapacity = (int)(capacity * NexConfig.fleetRequestCapMult);
		float newStock = fleetRequestStock + increment * days * NexConfig.fleetRequestIncrementMult;
		if (newStock > fleetRequestCapacity)
			newStock = fleetRequestCapacity;
		
		fleetRequestStock = Math.max(fleetRequestStock, newStock);	// doesn't decrease if we lose capacity suppliers
	}
	
	public int getFleetRequestCapacity() {
		return fleetRequestCapacity;
	}
	
	public float getFleetRequestStock() {
		return fleetRequestStock;
	}
	
	public void modifyFleetRequestStock(int amount) {
		fleetRequestStock += amount;
	}
	
	public int getFleetRequestCapacity(MarketAPI market) {
		int amount = 0;
		int size = (market.getSize() - 2);
		for (Industry ind : market.getIndustries()) {
			if (ind.isDisrupted()) continue;
			
			if (ind.getSpec().hasTag(Industries.TAG_PATROL))
				amount += 1;
			else if (ind.getSpec().hasTag(Industries.TAG_MILITARY))
				amount += 2 * size;
			else if (ind.getSpec().hasTag(Industries.TAG_COMMAND))
				amount += 3 * size;
		}
		amount *= 100;
		
		return amount;
	}
	
	public float getLowProfileMult(String factionId) {
		if (!DiplomacyTraits.hasTrait(factionId, TraitIds.LOWPROFILE))
			return 1;
		
		int empireSize = EconomyInfoHelper.getInstance().getCachedEmpireSize(factionId);
		int minSize = 5, maxSize = 25;
		float minMult = 0.5f, maxMult = 0.9f;
		if (empireSize <= minSize) return minMult;
		else if (empireSize >= maxSize) return maxMult;
		else {
			float alpha = (empireSize - minSize)/(maxSize - minSize);
			return NexUtilsMath.lerp(minMult, maxMult, alpha);
		}
	}
	
	public List<OffensiveFleetIntel> getActiveIntelCopy() {
		return new ArrayList<>(activeIntel);
	}
	
	@Override
	public void advance(float amount)
	{
		if (Global.getSector().isInNewGameAdvance())
			return;
		
		float days = Global.getSector().getClock().convertToDays(amount);
		
		tracker.advance(days);
		boolean elapsed = this.tracker.intervalElapsed();
		
		if (elapsed) {
			updateFleetRequestStock(tracker.getIntervalDuration());
		}
		
		// if still in invasion grace period, do nothing further
		if (daysElapsed < NexConfig.invasionGracePeriod)
		{
			daysElapsed += days;
			return;
		}
		
		// advance Remnant raid tracker
		if (Global.getSector().getClock().getCycle() >= 207) {
			remnantRaidInterval.advance(days);
			if (remnantRaidInterval.intervalElapsed())
				generateRemnantRaidFleet(null, null, 1);
		}
				
		if (!elapsed) {
			return;
		}
		List<RaidIntel> remove = new LinkedList();
		for (RaidIntel intel : activeIntel) {
			if (intel.isEnded() || intel.isEnding()) {
				remove.add(intel);
			}
		}
		this.activeIntel.removeAll(remove);
	
		processInvasionPoints();
		processTemplarInvasionPoints();
		processPirateRage();
	}
	
	public static void debugRemnantRaidFleet() {
		getManager().generateRemnantRaidFleet(null,	null, 1);
	}
	
	public static InvasionFleetManager getManager()
	{
		Map<String, Object> data = Global.getSector().getPersistentData();
		InvasionFleetManager manager = (InvasionFleetManager)data.get(MANAGER_MAP_KEY);
		return manager;
	}
	
	public static InvasionFleetManager create()
	{
		InvasionFleetManager manager = getManager();
		if (manager != null)
			return manager;
		
		Map<String, Object> data = Global.getSector().getPersistentData();
		manager = new InvasionFleetManager();
		data.put(MANAGER_MAP_KEY, manager);
		return manager;
	}
	
	@Override
	public boolean isDone()
	{
		return false;
	}
	
	@Override
	public boolean runWhilePaused()
	{
		return false;
	}
	
	public enum EventType { INVASION, RAID, RESPAWN, BASE_STRIKE, SAT_BOMB };
	
	// No longer used
	// only kept around because some classes I'm keeping for reference still reference it
	@Deprecated
	public static class InvasionFleetData
	{
		public CampaignFleetAPI fleet;
		public SectorEntityToken source;
		public SectorEntityToken target;
		public MarketAPI sourceMarket;
		public MarketAPI targetMarket;
		public float startingFleetPoints = 0.0F;
		public int marineCount = 0;
		public boolean noWait = false;
		public boolean noWander = false;
	
		public InvasionFleetData(CampaignFleetAPI fleet)
		{
			this.fleet = fleet;
		}
	}
}