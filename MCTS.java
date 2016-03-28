import java.util.*;
import static java.util.Arrays.*;

public class MCTS
{
	//Abbreviations.
	private static final int ys = Board.height, xs = Board.width, colors = Board.colors;

	//Random number generator.
	private static final Random rnd = new Random();

	//Temporary storage for solutions.
	private static final int[] h = new int[xs*ys/2];

	//The best solution found so far.
	private static int[] best = {-225*225-1};

	//Our default explorative factor.
	private static double defaultC = 0.021 * 5000;

	//The limit for resources left when we should start to decrease explorative factors.
	private static final double urgency_limit = 0.30;

	//Our current resources, the total resources (those we started with), and up to which depth moves should be "finalized".
	private static int inspect_lim = 0, start_lim = 0, break_depth = 0;

	//Hash table for duplication detection.
	private static final HashMap<Long,Node> map = new HashMap<Long,Node>();

	//Flags for: If the last simulation cleared the board,
	// if the last solution yielded is comes from traversing down a perfectly solved path,
	// and if any terminal node was encountered before urgency_limit.
	private static boolean bonus, solved, early_terminal;

	public static int[] metaSolve(final int[] board, final int tot, final int laps)
	{
		final int simlim = tot/laps; //Approximate number of resources per run.

		int[] ans = null;
		for(int i = 0; i<laps; i++)
		{
			solve(board,simlim);
			if(ans==null || best[0]>ans[0]) ans = copyOf(best, best.length);

			rnd.setSeed(System.currentTimeMillis() * 1000000007L); //Let's use a new random seed.
		}

		return ans;
	}

	// Yields a solution for the given board using simlim resources.
	public static int[] solve(final int[] board, final int simlim)
	{
		System.err.println("Running MCTS with "+simlim+" ru!");

		mcts_reset(); //Reset values to starting values.
		start_lim = inspect_lim = simlim;

		//Allocate resources for the S first moves.
		final int S = 30;
		final int[] resource_frame = new int[S];
		for(int i = 0, left = simlim; i<S; i++)
		{
			resource_frame[i] = (int)Math.max(64*32, left/7.0);
			left -= resource_frame[i];
		}
		int frame_lim = resource_frame[0];

		//Create the root node.
		Node root = new Node(board);

		//Runs iterations of MCTS as long there's resources.
		while(inspect_lim>0)
		{
			final int tmp = inspect_lim;

			bonus = solved = false; //Reset values.

			final int len = iterate(root,0,1); //Run an iteration.

			if(h[0]>best[0]) best = copyOf(h,len); //Store solution if new best.

			frame_lim -= tmp - inspect_lim; //Subtract the number of used resources in the iteration from the allocated resources.

			//We have run out of resources for this move... traverse down one level!
			if(frame_lim<=0)
			{
				break_depth++;
				frame_lim = break_depth>=S ? inspect_lim : resource_frame[break_depth];
			}
		}

		return best;
	}

	//Resets possible traces of previous runs.
	private static void mcts_reset()
	{
		best[0] = -225*225-1; map.clear();
		break_depth = 0;
		early_terminal = false;
	}

	// Runs an iteration of MCTS at the node at the given depth.
	// Up until reaching the node we have accumulated 'cum' points.
	private static int iterate(final Node root, final int cum, final int depth)
	{
		final Node[] child = root.child;
		final boolean[] own = root.own;
		final int[] score = root.score;
		final int len = child.length;

		//This could happpen for allocation per move strategies.
		if(root.t<0){ return inspect_lim = -1; }

		if(len==0) //We have reached a terminal node.
		{
			h[0] = root.topscore;
			leafhit(root);
			root.p.deactivateChild(root);
			root.t *= -1;
			--inspect_lim;
			solved = true;
			early_terminal |= inspect_lim>start_lim*urgency_limit;
			return -1;
		}

		//State pruninng.
		//if(cum+root.upperscore<=best[0]){ leafhit(root); cancel(root); --inspect_lim; return -1; }

		//Account for tree-traversal cost.
		if((depth&7)==0) inspect_lim--;

		//This is the LU-extension.
		if(!root.hasLeafHit && inspect_lim<start_lim*urgency_limit) root.c = Math.max(root.c*0.9995, 8);

		//Best index, chosen null child idx, number of encountered null-children.
		int bi = -1, bnull = -1, nullcnt = 0;

		if(depth<=break_depth) //The next child has been finalized on this level.
		{
			/*final int[] mvs = root.moves;
			for(int i = 0; i<len; i++)
				if(mvs[i<<1]==best[depth])
				{
					if(!own[i] && cum+score[i]>=child[i].cum) root.activateChild(i,cum+score[i]);
					bi = i;
					break;
				}*/

			int eq = 0;
			for(int i = 0; i<len; i++)
			{
				if(child[i]==null) //If a level is allocated few resources this could happen...
				{
					if(rnd.nextDouble()*++nullcnt<=1){ bnull = i; }
					continue;
				}

				if(!own[i] && cum+score[i]>=child[i].cum) root.activateChild(i,cum+score[i]);
				if(child[i].t>=0 && own[i])
					if(bi<0 || child[i].topscore>child[bi].topscore){ bi = i; eq = 1; }
					//else if(child[i].topscore==child[bi].topscore && rnd.nextDouble()*++eq<=1) bi = i;
			}

			if(bnull>=0) bi = bnull; //Null-preference has higher priority than finalized choice.
			if(bi<0){ break_depth--; return -1;} //If extremely few resources are allocated for some levels this could happen...
		}
		else //Let's choose next child based on UCB!
		{
			final double lnt = Math.log(root.t), c = root.c;
			double buct = Double.NEGATIVE_INFINITY;

			for(int i = 0; i<len; i++)
			{
				if(child[i]==null) //Null-preference.
				{
					if(rnd.nextDouble()*++nullcnt<=1){ bnull = i; }
				}
				else if(child[i].t>=0 && own[i]) //Ordinary case.
				{
					final double tmp = cum+score[i]+child[i].avg + c*Math.sqrt(lnt/child[i].t);
					if(tmp>buct){ bi = i; buct = tmp; }
				}
				else if(!own[i] && cum+score[i]>child[i].cum) //Reconquering.
				{
					if(child[i].t<0) //Solved children should not be "owned".
					{
						child[i].cum = cum+score[i];
						int j = -1;
						if(child[i].cum + child[i].topscore > best[0]) //Yippie, new highscore.
						{
							j = solvedPlayout(child[i], depth+1);
							h[depth] = root.moves[2*i];
							h[0] += score[i];
						}

						if(j<0) continue;
						else return j;
					}

					root.activateChild(i,cum+score[i]);

					final double tmp = cum+score[i]+child[i].avg + c*Math.sqrt(lnt/child[i].t);
					if(tmp>buct){ bi = i; buct = tmp; }
				}
			}

			if(bnull>=0) bi = bnull; //Null-preference.
			if(bi<0) //There was no suitable child.
			{
				root.p.deactivateChild(root);
				if(!root.isAlive()){ leafhit(root); root.live = 0; root.t *= -1; }
				return -1;
			}
		}

		final int[] mvs = root.moves; //Quick-access to the moves.

		//Get the next node to traverse down... it could be an unexpanded child...
		final Node nxt = child[bi]==null ? getChild(root, bi, mvs[2*bi], mvs[2*bi+1], cum) : child[bi];

		final boolean leafHitChoice = nxt.t<0; //That child could have been found by somebody else and solved.

		final int j =
		nxt.t==0 || nxt==deadchild ? tabuPlayout(nxt, depth+1) : //Ordinary playout or duplicate-hit.
		nxt.t>0 ? iterate(nxt, cum+score[bi], depth+1) : //Move on to next level in tree.
		solvedPlayout(nxt, depth+1); //We picked up a node that was already completely solved.

		if(j>=0) //Let's record our move...
		{
			h[depth] = mvs[2*bi];
			h[0] += score[bi];
			root.update(h[0]);
		}

		if(child[bi].t<0) //If our child was solved... (Do note!!! child[bi] == nxt is not necessarily true.)
		{
			if(leafHitChoice) leafhit(root); //Either it already was then we should account for the leaf hit...
			if(--root.live==0){  root.p.deactivateChild(root); root.t *= -1;} //...or it just became.
		}

		return j; //Return length of solution.
	}

	//Used in case of consistent duplication detection and state pruning.
	private static void cancel(final Node root)
	{
		final Node[] child = root.child;
		final boolean[] own = root.own;
		final int len = child.length;

		for(int i = 0; i<len; i++)
			if(child[i]!=null && child[i].t>=0 && own[i])
				cancel(child[i]);
		root.p.deactivateChild(root);
	}

	//Runs a random simulation from the node leaf at the given depth.
	//The length of the solution is returned.
	private static int playout(final Node leaf, final int depth)
	{
		if(leaf.t<0) return -1;

		inspect_lim--;

		final int[] board = copyOf(leaf.board,xs*ys);
		h[0] = 0;
		for(int j = depth; ; inspect_lim--)
		{
			final int len = Board.moves(board);

			if(len==0) //End of game.
			{
				h[0] += Board.endscore(board); bonus = Board.isEmpty(board);
				leaf.update(h[0]);
				return j;
			}

			//Performs a random move.
			final int mv = 2*rnd.nextInt(len>>1), i = h[j++] = Board.mvs[mv];
			h[0] = Board.doMove(board, i, Board.mvs[mv+1], h[0]);
		}
	}

	//Runs a simulation from the node leaf at the given depth, using the
	// TabuColorRandom default policy. The length of the solution is returned.
	private static int tabuPlayout(final Node leaf, final int depth)
	{
		if(leaf.t<0) return -1;

		inspect_lim--; //Account for inspection of state.

		final int[] board = copyOf(leaf.board,xs*ys);

		//Pick the tabu color.
		for(int i = 1; i<=colors; i++) h[i] = 0;
		for(int i = 0; i<ys*xs; i++) ++h[board[i]];
		int tabu = 1; h[0] = 1;
		for(int i = 2; i<=colors; i++)
			if(h[i]>h[tabu]){ tabu=i; h[0]=1; }
			else if(h[i]==h[tabu] && ++h[0]*rnd.nextDouble()<1) tabu = i;

		h[0] = 0; //Reset score.

		//Count number of blocks left.
		int blocks = 0;
		for(int i = 1; i<=colors; i++) blocks += h[i];

		for(int j = depth; ; inspect_lim--)
		{
			//We only use the simulation strategy if there's a significant number of blocks.
			final int len = blocks>48 ? Board.tabuMoves(board,tabu) : Board.moves(board);

			if(len==0) //End of game.
			{
				h[0] += Board.endscore(board); bonus = Board.isEmpty(board);
				leaf.update(h[0]);
				return j;
			}

			//Choose ranom move, perform move, account for removed blocks.
			final int mv = 2*rnd.nextInt(len>>1), i = h[j++] = Board.mvs[mv];
			h[0] = Board.doMove(board, i, Board.mvs[mv+1], h[0]);
			blocks -= Board.mvs[mv+1];
		}
	}

	//Extracts the optimal solution from the solved node root.
	//Returns the length of the solution.
	private static int solvedPlayout(Node root, final int depth)
	{
		solved = true; //Tell the class that this function was just run...

		h[0] = 0;
		for(int j = depth; ;)
		{
			if((j&7)==0) --inspect_lim; //Traversal cost.

			final Node[] child = root.child;
			final int[] score = root.score;
			final int len = child.length;

			if(len==0) //End of game.
			{
				h[0] += root.topscore; bonus = Board.isEmpty(root.board);
				return j;
			}

			//Lets pick the best move.
			int bi = 0;
			for(int i = 1; i<len; i++)
			{
				if(child[i].topscore + score[i]>child[bi].topscore + score[bi])
					bi = i;
			}

			//Record move, traverse down the tree.
			h[0] += score[bi];
			h[j++] = root.moves[bi<<1];
			root = child[bi];
		}
	}

	//Global node used to represent dead ends etc.
	private static final Node deadchild = new Node();

	//Returns the child of the node p that is reached by applying move i.
	//cum is the accumulated score of reaching p.
	//area is the number of blocks the move remove.
	private static Node getChild(final Node p, final int bi, final int i, final int area, final int cum)
	{
		final int[] board = copyOf(p.board,xs*ys);
		final int score = Board.doMove(board, i, area, 0);

		//Duplication-check stuff.
		final long hash = Board.hash(board);
		final Node tmp = map.get(hash);
		if(tmp!=null)
		{
			p.child[bi] = tmp; p.own[bi] = false; --p.cnt;
			if(score+cum<=tmp.cum)
			{
				if(p.cnt==0 && p.p!=null) p.p.deactivateChild(p);
				return deadchild;
			}
			else
			{
				p.activateChild(bi,score+cum); //Swap parent.
				return tmp;
			}
		}

		final Node kid = new Node(p,board,cum+score);
		map.put(hash, kid);
		//------

		return p.child[bi] = kid;
	}

	//Class representing a node of the MCTS search tree.
	private static class Node
	{
		Node p; //Parent node.

		//The board, the available moves, the score of move #i.
		final int[] board, moves, score;

		//The (best) cumulative score leading to this state.
		int cum;

		//Number of visits, number of active child nodes, number of unsolved child nodes, best score from this node.
		int t, cnt, live, topscore; //t<0 --> dead node

		//Is there a terminal node in this subtree.
		boolean hasLeafHit = false;

		//An upper bound on the maximum possible score achievable.
		final int upperscore;

		//Avg score, explorative factor.
		double avg, c = defaultC*1.00 + rnd.nextDouble()*defaultC*0.00;

		//Child nodes.
		final Node[] child;

		//If we are the owner of node #1.
		final boolean[] own;

		//Creates a null-child.
		Node()
		{
			board = moves = score = null;
			child = null;
			t = -1;
			own = null; upperscore = 0;
		}

		//Creates a root representing the given board.
		Node(final int[] b) //Root
		{
			board = b;
			moves = Board.getMoves(b);
			t = 0;
			avg = 0;
			topscore = Integer.MIN_VALUE;
			upperscore = Board.upperscore(b);
			child = new Node[live = cnt = moves.length/2];
			own = new boolean[cnt];
			fill(own, true);
			score = new int[cnt];
			for(int i = 1; i<moves.length; i+=2) score[i>>1] = (moves[i]-2)*(moves[i]-2);
		}

		//Creates a normal node representing the given board b, with parent state/node p,
		//that has been reached by an accumulated score of c.
		Node(final Node p, final int[] b, final int c)
		{
			this(b);
			this.p = p;
			cum = c;
		}

		//Records an iteration yielding the given score in this node.
		void update(int sample)
		{
			if(solved) return; //Scores yielded by solvedPlayout() shouldn't be recorder.
			//if(bonus) sample -= 1000; //Long-term ignore
			++t;
			avg += (sample-avg)/t;
			if(sample>topscore) topscore = sample;
		}

		//Revokes this node's ownership of the given child node.
		void deactivateChild(final Node kid)
		{
			for(int i = 0; i<child.length; i++)
				if(child[i]==kid)
				{
					if(own[i])
					{
						subtract(child[i],this);
						if(--cnt==0 && p!=null) p.deactivateChild(this);
						own[i] = false;
					}

					break;
				}
		}

		//Makes this node the owner of child #i reached using the given cumulative score.
		void activateChild(final int i, final int cum) //cum includes score[i].
		{
			final Node kid = child[i];
			kid.p.deactivateChild(kid);
			own[i] = true;
			kid.cum = cum;
			kid.p = this;
			++cnt;
			add(kid,this);
		}

		//Finds the index of the given child node.
		int getIndex(final Node kid)
		{
			for(int i = 0; i<child.length; i++)
				if(child[i]==kid)
					return i;
			return -1;
		}

		//Returns whether we own the given child node or not.
		boolean owns(final Node kid)
		{
			for(int i = 0; i<child.length; i++)
				if(child[i]==kid)
					return own[i];
			return false;
		}

		//Returns whether there are unsolved child nodes of this node.
		boolean isAlive()
		{
			for(int i = 0; i<child.length; i++)
				if(child[i]==null || child[i].t>=0)
					return true;
			return false;
		}
	}

	//Records that a terminal node is present in all ancestors of the given terminal node, and increases their C value.
	private static void leafhit(Node leaf)
	{
		for(double mul = 1.05; leaf!=null; mul = Math.max(mul*0.999,1.001), leaf=leaf.p)
		{
			leaf.c *= mul;
			leaf.hasLeafHit = true;
		}
	}

	//Subtracts the statistics of node kid from node p and all its ancestors.
	private static void subtract(Node kid, Node p)
	{
		double sub = kid.avg; final int t = kid.t;
		if(t<0) return;
		for(; p!=null; kid=p, p=p.p)
		{
			sub += p.score[p.getIndex(kid)];
			p.avg = (p.avg*p.t - sub*t)/(p.t - t);
			p.t -= t;
		}
	}

	//Add the statistics of node kid to node p and all its ancestors.
	private static void add(Node kid, Node p)
	{
		double add = kid.avg; final int t = kid.t;
		if(t<0) return;
		for(; p!=null; kid=p, p=p.p)
		{
			add += p.score[p.getIndex(kid)];
			p.avg = (p.avg*p.t + add*t)/(p.t + t);
			p.t += t;
		}
	}
}