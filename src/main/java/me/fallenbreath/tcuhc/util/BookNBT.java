package me.fallenbreath.tcuhc.util;

import me.fallenbreath.tcuhc.UhcGameColor;
import me.fallenbreath.tcuhc.UhcGameManager;
import me.fallenbreath.tcuhc.UhcGamePlayer;
import me.fallenbreath.tcuhc.UhcGameTeam;
import me.fallenbreath.tcuhc.options.Option;
import me.fallenbreath.tcuhc.options.Options;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BookNBT {
	private static final String BOOK_KIND_KEY = "TcUhcBookKind";
	private static final String CONFIG_BOOK_TITLE = "UHC 游戏配置";
	private static final int CONFIG_BOOK_PAGE_COUNT = 5;

	public static final String CONFIG_BOOK = "config";
	public static final String PLAYER_BOOK = "player";
	public static final String ADJUST_BOOK = "adjust";
	
	public static List<RawFilteredPair<Text>> appendPageText(List<RawFilteredPair<Text>> pages, Text text) {
		pages.add(RawFilteredPair.of(text));
		return pages;
	}
	
	public static MutableText createTextEvent(String text, String cmd, String hover, Formatting color) {
		MutableText res = Text.literal(text);
		if (color != null) res = res.formatted(color);
		if (cmd != null) res = res.styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)));
		if (hover != null) res = res.styled(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
		return res;
	}

	public static MutableText createSuggestTextEvent(String text, String cmd, String hover, Formatting color) {
		MutableText res = Text.literal(text);
		if (color != null) res = res.formatted(color);
		if (cmd != null) res = res.styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd)));
		if (hover != null) res = res.styled(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
		return res;
	}
	
	public static Text createOptionText(Optional<Option> opt) {
		// Each option row is a self-contained control strip: decrement, current value, increment.
		// The middle value enters chat-input mode so multi-page books do not need custom client UI.
		return opt.map(option -> createTextEvent(option.getName(), null, option.getDescription(), Formatting.BLUE)
				.append(createTextEvent(" < ", "/uhc option " + option.getId() + " sub", option.getDecString(), Formatting.RED))
				.append(createTextEvent(getOptionDisplayValue(option), "/uhc option " + option.getId() + " set",
						option.getId().equals("compassInterval") ? "输入 0 为自动，或输入 1～300 秒" : "点击输入数值", Formatting.GOLD))
				.append(createTextEvent(" >", "/uhc option " + option.getId() + " add", option.getIncString(), Formatting.GREEN))
				.append(Text.literal("\n"))).orElse(Text.literal("未知配置项"));
	}

	private static String getOptionDisplayValue(Option option) {
		if (option.getId().equals("compassInterval")) {
			return option.getIntegerValue() == 0 ? "自动" : option.getIntegerValue() + "秒";
		}
		return option.getStringValue();
	}

	public static int getConfigBookPageCount()
	{
		return CONFIG_BOOK_PAGE_COUNT;
	}

	private static Text createConfigBookTitle(String title)
	{
		return Text.literal(title + "\n").formatted(Formatting.DARK_AQUA);
	}
	
	public static ItemStack createWrittenBook(String author, String title, List<RawFilteredPair<Text>> pages, String kind) {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of(title), author, 0, pages, true));
		NbtCompound nbt = new NbtCompound();
		nbt.putString(BOOK_KIND_KEY, kind);
		book.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
		return book;
	}

	public static boolean isTcUhcBook(ItemStack stack) {
		return getBookKind(stack) != null;
	}

	public static boolean isTcUhcBook(ItemStack stack, String kind) {
		String bookKind = getBookKind(stack);
		return bookKind != null && bookKind.equals(kind);
	}

	public static String getBookKind(ItemStack stack) {
		NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (customData == null) {
			return null;
		}
		NbtCompound nbt = customData.copyNbt();
		return nbt.contains(BOOK_KIND_KEY) ? nbt.getString(BOOK_KIND_KEY) : null;
	}
	
	public static Text createReturn() {
		return Text.literal("\n");
	}
	
	private static Text createConfigBookNavigation(int page)
	{
		MutableText navigation = Text.literal("");
		if (page > 0)
		{
			navigation.append(createTextEvent("< 上一页", "/uhc configPage " + (page - 1), "查看上一组设置", Formatting.GREEN));
		}
		else
		{
			navigation.append(Text.literal("< 上一页").formatted(Formatting.DARK_GRAY));
		}
		navigation.append(createTextEvent(
				"   " + (page + 1) + "/" + CONFIG_BOOK_PAGE_COUNT + "   ",
				"/uhc configPagePrompt",
				"点击后在聊天栏输入 1-" + CONFIG_BOOK_PAGE_COUNT + " 的页码并发送",
				Formatting.GOLD
		));
		if (page < CONFIG_BOOK_PAGE_COUNT - 1)
		{
			navigation.append(createTextEvent("下一页 >", "/uhc configPage " + (page + 1), "查看下一组设置", Formatting.GREEN));
		}
		else
		{
			navigation.append(Text.literal("下一页 >").formatted(Formatting.DARK_GRAY));
		}
		return navigation.append(Text.literal("\n"));
	}

	private static Text getConfigBookPage(UhcGameManager gameManager, int page)
	{
		Options options = gameManager.getOptions();
		int safePage = Math.max(0, Math.min(page, CONFIG_BOOK_PAGE_COUNT - 1));
		MutableText text;
		switch (safePage)
		{
			case 0:
				text = Text.empty()
						.append(createConfigBookTitle("基础设置"))
						.append(createOptionText(options.getOption("gameMode")))
						.append(createOptionText(options.getOption("battleType")))
						.append(createOptionText(options.getOption("levelType")))
						.append(createOptionText(options.getOption("disableOceanBiomes")))
						.append(createOptionText(options.getOption("randomTeams")))
						.append(createOptionText(options.getOption("teamCount")))
						.append(createOptionText(options.getOption("enemyCompass")))
						.append(createOptionText(options.getOption("compassInterval")));
				break;
			case 1:
				text = Text.empty()
						.append(createConfigBookTitle("游戏设置"))
						.append(createOptionText(options.getOption("difficulty")))
						.append(createOptionText(options.getOption("weather")))
						.append(createOptionText(options.getOption("daylightCycle")))
						.append(createOptionText(options.getOption("friendlyFire")))
						.append(createOptionText(options.getOption("teamCollision")))
						.append(createOptionText(options.getOption("greenhandProtect")))
						.append(createOptionText(options.getOption("forceViewport")))
						.append(createOptionText(options.getOption("deathBonus")))
						.append(createOptionText(options.getOption("TNTBomber")));
				break;
			case 2:
				text = Text.empty()
						.append(createConfigBookTitle("时间设置"))
						.append(createOptionText(options.getOption("borderStart")))
						.append(createOptionText(options.getOption("borderEnd")))
						.append(createOptionText(options.getOption("borderFinal")))
						.append(createOptionText(options.getOption("gameTime")))
						.append(createOptionText(options.getOption("borderStartTime")))
						.append(createOptionText(options.getOption("borderEndTime")))
						.append(createOptionText(options.getOption("netherCloseTime")))
						.append(createOptionText(options.getOption("caveCloseTime")))
						.append(createOptionText(options.getOption("greenhandTime")));
				break;
			case 3:
				text = Text.empty()
						.append(createConfigBookTitle("世界设置"))
						.append(createOptionText(options.getOption("merchantFrequency")))
						.append(createOptionText(options.getOption("oreFrequency")))
						.append(createOptionText(options.getOption("chestFrequency")))
						.append(createOptionText(options.getOption("trappedChestFrequency")))
						.append(createOptionText(options.getOption("chestItemFrequency")))
						.append(createOptionText(options.getOption("mobCount")))
						.append(createOptionText(options.getOption("pregenerateOnStart")))
						.append(createOptionText(options.getOption("netherPregenerate")))
						.append(createOptionText(options.getOption("pregenerateParallelism")));
				break;
			default:
				text = Text.empty()
						.append(createConfigBookTitle("操作"))
						.append(createTextEvent("     重置玩法\n", "/uhc reset gameplay", "恢复玩法、时间和队伍的默认设置", Formatting.GOLD))
						.append(createTextEvent("    重置生成\n", "/uhc reset generation", "恢复矿物、宝箱、商人和怪物生成频率；需要重新生成地形", Formatting.GOLD))
						.append(createTextEvent("       重新生成\n", "/uhc regen", "重新生成地形", Formatting.LIGHT_PURPLE))
						.append(createTextEvent(" 强制开始（跳过预生成）\n", "/uhc forceStart", "管理员可在预生成未完成时提前开始，需二次确认", Formatting.RED))
						.append(createTextEvent("         开始游戏！\n", "/uhc start", "开始本局 UHC", Formatting.LIGHT_PURPLE));
				break;
		}
		// Put navigation at the top so a single-page book never hides it below the page limit.
		return ((MutableText)createConfigBookNavigation(safePage)).append(text);
	}

	public static ItemStack getConfigBook(UhcGameManager gameManager, int page) {
		List<RawFilteredPair<Text>> pages = new ArrayList<RawFilteredPair<Text>>();
		appendPageText(pages, getConfigBookPage(gameManager, page));
		return createWrittenBook("sbGP", CONFIG_BOOK_TITLE, pages, CONFIG_BOOK);
	}
	
	public static ItemStack getPlayerBook(UhcGameManager gameManager) {
		Options options = gameManager.getOptions();
		int teamCount = options.getIntegerOptionValue("teamCount");
		boolean randomTeams = options.getBooleanOptionValue("randomTeams");
		MutableText text = Text.literal("选择队伍\n\n");
		String line = "***********************\n";
		text.append(createTextEvent(line, "/uhc select 8", "点击切换为观察者", Formatting.GRAY));
		if (randomTeams)
			text.append(createTextEvent(line, "/uhc select 9", "点击加入战斗", Formatting.BLACK));
		else {
			switch ((UhcGameManager.EnumMode) options.getOptionValue("gameMode")) {
				// KING uses the same colour-team selection as NORMAL - the king is simply the first
				// player of each team. Without this branch the book renders only the observer row,
				// so nobody can join a team and /uhc start always refuses with "有玩家未选队".
				case KING:
				case NORMAL: {
					text.append(createTextEvent(line, "/uhc select 9", "点击加入随机队伍", Formatting.BLACK));
					for (int i = 0; i < teamCount; i++) {
						UhcGameColor color = UhcGameColor.getColor(i);
						text.append(createTextEvent(line, "/uhc select " + color.getId(), "点击加入" + color.name + "队", color.chatColor));
					}
					break;
				}
				case SOLO: 
				case GHOST:
				case BOMBER:
					text.append(createTextEvent(line, "/uhc select 9", "点击加入战斗", Formatting.BLACK));
					break;
				case BOSS: {
					text.append(createTextEvent(line, "/uhc select 0", "点击成为 Boss 阵营", Formatting.RED));
					text.append(createTextEvent(line, "/uhc select 1", "点击成为挑战者阵营", Formatting.BLUE));
					break;
				}
				case HUNTER:
					text.append(createTextEvent(line, "/uhc select 0", "点击成为猎物", Formatting.RED));
					text.append(createTextEvent(line, "/uhc select 1", "点击成为猎人", Formatting.BLUE));
					break;
				case GHOSTHUNTER:
					text.append(createTextEvent(line, "/uhc select 0", "点击成为幽灵", Formatting.RED));
					text.append(createTextEvent(line, "/uhc select 1", "点击成为猎人", Formatting.BLUE));
					break;
			}
		}
		List<RawFilteredPair<Text>> pages = new ArrayList<RawFilteredPair<Text>>();
		appendPageText(pages, text);
		
		return createWrittenBook("sbGP", "UHC 队伍选择", pages, PLAYER_BOOK);
	}
	
	public static Text createPlayerText(UhcGamePlayer player) {
		MutableText text = createTextEvent(player.getName(), null, player.getName(), player.getTeam().getTeamColor().chatColor);
		if (player.isAlive())
			text.append(createTextEvent(" 存活\n", "/uhc adjust kill " + player.getName(), "点击判定 " + player.getName() + " 死亡", Formatting.DARK_GREEN));
		else text.append(createTextEvent(" 死亡\n", "/uhc adjust resu " + player.getName(), "点击复活 " + player.getName(), Formatting.DARK_RED));
		return text;
	}
	
	public static ItemStack getAdjustBook(UhcGameManager gameManager) {
		Options options = gameManager.getOptions();
		List<RawFilteredPair<Text>> pages = new ArrayList<RawFilteredPair<Text>>();
		
		switch ((UhcGameManager.EnumMode) options.getOptionValue("gameMode")) {
			case BOSS:
			case HUNTER:
			case GHOSTHUNTER:
			case NORMAL:
			case KING: {
				for (UhcGameTeam team : gameManager.getUhcPlayerManager().getTeams()) {
					MutableText text = Text.literal(team.getColorfulTeamName() + "\n\n");
					for (UhcGamePlayer player : team.getPlayers()) {
						text.append(createPlayerText(player));
					}
					appendPageText(pages, text);
				}
				break;
			}
			case SOLO:
			case GHOST:
			case BOMBER: {
				MutableText text = Text.literal(Formatting.LIGHT_PURPLE + "所有玩家\n\n");
				for (UhcGamePlayer player : gameManager.getUhcPlayerManager().getCombatPlayers()) {
					text.append(createPlayerText(player));
				}
				appendPageText(pages, text);
			}
		}
		
		MutableText text = Text.literal("结束\n\n");
		text.append(createTextEvent("结束调整", "/uhc adjust end", "点击移除这本调整书", Formatting.LIGHT_PURPLE));
		appendPageText(pages, text);
		return createWrittenBook("sbGP", "UHC 对局调整", pages, ADJUST_BOOK);
	}

}
