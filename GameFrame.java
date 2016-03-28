import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class GameFrame extends JFrame
{
	private final Game game;

	public GameFrame(String title) throws Exception
	{
		game = new Game();

		add(game, BorderLayout.CENTER);

		setTitle(title);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		pack();

		setVisible(true);
		game.requestFocus();

		game.runSolver(); //Remove this line for human play.
	}

	public static void main(String[] BwaKell) throws Exception
	{
		new GameFrame("SameGame");
	}
}