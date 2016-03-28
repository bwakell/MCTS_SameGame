import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.util.*;
import java.io.*;

public class Game extends JPanel implements MouseListener, MouseMotionListener
{
	//Double buffering.
	private Image dbi;
	private Graphics dbg;

	//Size of the graphical area.
	private final int width = 800, height = 600, w = width, h = height;

	//Bakground color.
	private final Color bg;

	//Size of a block (a block is a square), size of the board in number of blocks, and the number of colors.
	private static final int tile = 32, ys = Board.height, xs = Board.width, colors = Board.colors;

	//Colors are logically presented as integers [1..colors]. Assigns the logical color i to graphical color fix[i-1].
	private static final Color[] fix = {Color.red, Color.blue, Color.green, Color.yellow, Color.cyan};

	//A column-sized array with the background color.
	private static final Color[] zero = new Color[ys];

	//The graphical board.
	private final Color[][] board = new Color[xs][ys];

	//The logical board.
	final int[] b = new int[xs*ys];

	//Keeps track of highlighting of groups.
	private final boolean[][] mark = new boolean[xs][ys];

	//Our current score.
	private int score = 0;

	// Creates the board, both the logical and graphical.
	public Game() throws Exception
	{
		bg = Color.black;
		for(int i = 0; i<ys; i++) zero[i] = bg;

		// Load the board here, keep the format in mind.
		Scanner in = new Scanner(new File("test.smg"));
		for(int i = 0; i<xs; i++)
			for(int j = 0; j<ys; j++)
				board[i][j] = fix[(b[i*ys+j] = in.nextInt())-1];
		in.close();

		setPreferredSize(new Dimension(width,height));

		addMouseListener(this);
		addMouseMotionListener(this);
	}

	// Draws the state of the graphical board, executed by calling repaint().
	public void paint(Graphics g)
	{
        if(dbi==null)
        {
            dbi = createImage(width, height);
            dbg = dbi.getGraphics();
        }

		dbg.setColor(bg);
		dbg.fillRect(0,0,width,height);

		for(int i = 0; i<xs; i++)
			for(int j = 0; j<ys; j++)
			{
				if(mark[i][j]) dbg.setColor(Color.white);
				else dbg.setColor(board[i][j]);
				dbg.fillRect(i*tile,h-ys*tile+j*tile,tile,tile);
			}

		dbg.setColor(Color.white);
		dbg.drawString("Score: " + score, 8, 16);

		g.drawImage(dbi,0,0,width,height,null);
	}

	/*** <Graphical board logic> ***/
	// Calculates the size of the group containing the block at (x,y) of color prv,
	// and mark all blocks/positions of the group.
	private int dfsMark(final int x, final int y, final Color prv)
	{
		if(x<0 || x>=xs || y<0 || y>=ys || prv!=board[x][y] || mark[x][y]) return 0;
		mark[x][y] = true;
		int sum = 1;
		sum += dfsMark(x-1,y,prv);
		sum += dfsMark(x+1,y,prv);
		sum += dfsMark(x,y-1,prv);
		sum += dfsMark(x,y+1,prv);
		return sum;
	}

	// Eliminates the group containing the block at (x,y) of color prv.
	// The size of the eliminated group is returned.
	private int dfsKill(final int x, final int y, final Color prv)
	{
		if(x<0 || x>=xs || y<0 || y>=ys || prv!=board[x][y]) return 0;
		board[x][y] = bg;
		int sum = 1;
		sum += dfsKill(x-1,y,prv);
		sum += dfsKill(x+1,y,prv);
		sum += dfsKill(x,y-1,prv);
		sum += dfsKill(x,y+1,prv);
		return sum;
	}

	// Applies game physics to the given graphical column.
	private void redCol(final Color[] col)
	{
		for(int i = ys-1, cnt = 0; i>=0; i--)
			if(col[i]==bg) ++cnt;
			else if(cnt>0){ col[i+cnt] = col[i]; col[i] = bg; }
	}

	// Applies the column-shift-left logic to the graphical board.
	private void redRow()
	{
		for(int i = 0, cnt = 0; i<xs; i++)
			if(board[i][ys-1]==bg) ++cnt;
			else if(cnt>0){ board[i-cnt] = board[i]; board[i] = zero; }
	}

	// Attempts to perform the move of removing the group of the block at (x,y).
	// false is returned if the was illegal, e.g. there is no block at (x,y) or the group of (x,y) is of size 1,
	// otherwise true is returned.
	private boolean makeMove(final int x, final int y)
	{
		if(board[x][y]==bg){ System.err.println("ERROR "+x+"-"+y); return false; }

		final Color tmp = board[x][y];
		final int sum = dfsKill(x,y,board[x][y]);

		if(sum<2){ System.err.println("ERROR "+x+"-"+y); board[x][y] = tmp; return false; }

		score += (sum - 2)*(sum - 2);
		for(int i = 0; i<xs; i++) redCol(board[i]);
		redRow();

		System.err.println("Made move: " + x + " " + y);
		if(gameOver()){ score += endScore(); System.err.println("GameOver!"); }

		return true;
	}

	// Returns true if the game is over, i.e. there exists no more move.
	private boolean gameOver()
	{
		for(int i = 0; i<xs; i++)
			for(int j = 0; j<ys; j++)
				if(board[i][j]!=bg && dfsMark(i,j,board[i][j])>1)
				{
					for(i = 0; i<xs; i++) Arrays.fill(mark[i],false);
					return false;
				}
		return true;
	}

	// Calculates the end game score of the board.
	private int endScore()
	{
		if(board[0][ys-1]==bg) return 1000;
		int ans = 0;
		final int[] cnt = new int[colors];
		for(int i = 0; i<xs; i++) Arrays.fill(mark[i],false);
		for(int i = 0; i<xs; i++)
			for(int j = 0; j<ys; j++)
				if(board[i][j]!=bg)
				{
					final int area = dfsMark(i,j,board[i][j]);
					if(area>1) ans += (area-2)*(area-2);
					else
						for(int k = 0; k<colors; k++)
							if(board[i][j]==fix[k])
								cnt[k]++;
				}
		for(int i = 0; i<colors; i++) ans -= (cnt[i]-2)*(cnt[i]-2);
		for(int i = 0; i<xs; i++) Arrays.fill(mark[i],false);
		return ans;
	}
	/*** </Graphical board logic> ***/

	/*** <Mouse click> ***/
	// Logic for detecting if a group was clicked on.
	public void mouseClicked(MouseEvent me)
	{
		final int x = me.getX()/tile, y = (me.getY() - h+ys*tile)/tile;
		if(x<xs && y<ys && y>=0 && board[x][y]!=bg)
		{
			makeMove(x,y); //Attempt to make the specified move.
		}
		for(int i = 0; i<xs; i++) Arrays.fill(mark[i],false);
		repaint();
		marked = false;
	}

	//Keep tracks whether a group is currently being highlighted or not.
	private boolean marked = false;

	// Logic for highlighting of groups when hovered by the mouse.
	public void mouseMoved(MouseEvent me)
	{
		final int x = me.getX()/tile, y = (me.getY() - h+ys*tile)/tile;
		if(x<xs && y<ys && y>=0 && board[x][y]!=bg)
		{
			for(int i = 0; i<xs; i++) Arrays.fill(mark[i],false);
			if(dfsMark(x,y,board[x][y])<2) mark[x][y] = marked = false;
			else marked = true;
			repaint();
		}
		else if(marked)
		{
			for(int i = 0; i<xs; i++) Arrays.fill(mark[i],false);
			repaint();
			marked = false;
		}
	}

	public void mouseEntered(MouseEvent me){}
	public void mouseExited(MouseEvent me){}
	public void mousePressed(MouseEvent me){}
	public void mouseReleased(MouseEvent me){}
	public void mouseDragged(MouseEvent me){}
	/*** </Mouse click> ***/

	/*** <Algorithm interaction> ***/
	// Solves the board using an algorithm.
	// The time and score of the algorithm is presented, followed by a visualization of the solution.
	public void runSolver()
	{
		long tid = System.currentTimeMillis();
		final int[] h = MCTS.solve(b,10*64*10000); //Call your algorithm here.
		tid = System.currentTimeMillis() - tid;

		System.err.println("Found solution in " + tid + " ms!");
		System.err.println("Score: " + h[0]);

		simulate(h);
	}

	// Visualizes the given solution h to SameGame by a simulation of it.
	private void simulate(final int[] h)
	{
		System.err.println("Number of moves: " + (h.length-1));

		for(int i = 1; i<h.length; i++)
		{
			try{ Thread.sleep(512); } catch(Exception e){}
			makeMove(h[i]/ys,h[i]%ys);
			repaint();
		}

		System.err.println("Simulation done");
	}
	/*** </Algorithm interaction> ***/
}