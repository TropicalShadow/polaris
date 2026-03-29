package club.tesseract.polar.commands;

import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;

public class TeleportCommand extends Command {

    public TeleportCommand() {
        super("tp", "teleport");

        var x = ArgumentType.Double("x");
        var y = ArgumentType.Double("y");
        var z = ArgumentType.Double("z");

        setDefaultExecutor((sender, context) -> {
            sender.sendMessage("Usage: /tp <x> <y> <z>");
        });

        addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command");
                return;
            }

            double px = context.get(x);
            double py = context.get(y);
            double pz = context.get(z);

            player.teleport(new Pos(px, py, pz));
            player.sendMessage("Teleported to " + px + ", " + py + ", " + pz);
        }, x, y, z);
    }
}
