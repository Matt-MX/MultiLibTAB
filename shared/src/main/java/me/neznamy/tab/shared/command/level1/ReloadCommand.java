package me.neznamy.tab.shared.command.level1;

import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.command.SubCommand;

/**
 * Handler for "/tab reload" subcommand
 */
public class ReloadCommand extends SubCommand {

	/**
	 * Constructs new instance
	 */
	public ReloadCommand() {
		super("reload", "tab.reload");
	}

	@Override
	public void execute(TabPlayer sender, String[] args) {
		TAB.getInstance().unload();
		sendMessage(sender, TAB.getInstance().load());
	}
}