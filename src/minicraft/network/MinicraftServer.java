package minicraft.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.io.IOException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.StandardOpenOption;
import minicraft.Game;
import minicraft.entity.Entity;
import minicraft.entity.ItemEntity;
import minicraft.entity.RemotePlayer;
import minicraft.entity.Chest;
import minicraft.saveload.Save;
import minicraft.saveload.Load;
import minicraft.level.Level;
import minicraft.screen.ModeMenu;
import minicraft.screen.WorldSelectMenu;
import minicraft.item.Item;
import minicraft.item.Items;

public class MinicraftServer extends Thread implements MinicraftConnection {
	
	public ArrayList<RemotePlayer> clientList = new ArrayList<RemotePlayer>();
	
	private Game game;
	private DatagramSocket socket = null;
	
	private String worldPath;
	
	public MinicraftServer(Game game) {
		super("MinicraftServer");
		this.game = game;
		game.ISHOST = true; // just in case.
		//game.player.remove(); // the server has no player...
		
		worldPath = Game.gameDir + "/saves/" + WorldSelectMenu.worldname;
		
		try {
			System.out.println("opening server socket...");
			socket = new DatagramSocket(MinicraftProtocol.PORT);
		} catch (SocketException ex) {
			System.err.println("failed to open server socket.");
			ex.printStackTrace();
		}
	}
	
	public void run() {
		while(isConnected()) {
			byte[] data = new byte[1024];
			DatagramPacket packet = new DatagramPacket(data, data.length);
			try {
				socket.receive(packet);
			} catch (IOException e) {
				e.printStackTrace();
			}
			parsePacket(packet.getData(), packet.getAddress(), packet.getPort());
		}
		
		System.out.println("closing server socket");
		
		endConnection();
	}
	
	public void sendEntityUpdate(Entity e) {
		broadcastData(prependType(MinicraftProtocol.InputType.ENTITY, (e.eid+";"+e.getUpdates()).getBytes()));
	}
	
	public void sendTileUpdate(int depth, int x, int y) {
		byte lvlidx = (byte) Game.lvlIdx(depth);
		Level curLevel = Game.levels[lvlidx];
		int pos = x + curLevel.w * y;
		byte[] posbytes = String.valueOf(pos).getBytes();
		byte[] tiledata = new byte[posbytes.length+3];
		tiledata[0] = lvlidx;
		tiledata[1] = curLevel.tiles[pos];
		tiledata[2] = curLevel.data[pos];
		for(int i = 0; i < posbytes.length; i++)
			tiledata[i+3] = posbytes[i];
		
		broadcastData(prependType(MinicraftProtocol.InputType.TILE, tiledata));
	}
	
	public void sendEntityAddition(Entity e) {
		broadcastData(prependType(MinicraftProtocol.InputType.ADD, Save.writeEntity(e, false).getBytes())); // don't send it to yourself...
	}
	
	public void sendEntityRemoval(Entity e) {
		broadcastData(prependType(MinicraftProtocol.InputType.REMOVE, String.valueOf(e.eid).getBytes()));
		// the player is to hopefully prevent updates to the host client's level twice. (this comment is obsolete)
	}
	
	/*public void sendNotification(String note, int ntime) {
		for(RemotePlayer client: clientList) {
			thread.sendNotification(note, ntime);
		}
	}*/
	
	public ArrayList<String> getClientNames() {
		ArrayList<String> names = new ArrayList<String>();
		for(RemotePlayer client: clientList) {
			names.add(client.getClientName());
		}
		
		return names;
	}
	
	public File[] getRemotePlayerFiles() {
		File saveFolder = new File(worldPath);
		
		File[] clientSaves = saveFolder.listFiles(new FilenameFilter() {
			public boolean accept(File file, String name) {
				return name.startsWith("RemotePlayer");
			}
		});
		
		return clientSaves;
	}
	public File getRemotePlayerFile(NetworkInterface computer) {
		File[] clientFiles = getRemotePlayerFiles();
		String playerdata = "";
		for(File file: clientFiles) {
			String mac = "";
			try {
				BufferedReader br = new BufferedReader(new FileReader(file));
				try {
					mac = br.readLine();
				} catch(IOException ex) {
					System.err.println("failed to read line from file.");
					ex.printStackTrace();
				}
			} catch(FileNotFoundException ex) {
				System.err.println("couldn't find remote player file: " + file);
				ex.printStackTrace();
			}
			
			try {
				if(Arrays.equals(mac.getBytes(), computer.getHardwareAddress())) {
					/// this player has been here before.
					if (Game.debug) System.out.println("remote player file found; returning file " + file.getName());
					return file;
				}
			} catch(SocketException ex) {
				System.err.println("problem fetching mac address.");
				ex.printStackTrace();
			}
		}
		
		return null;
	}
	public String getRemotePlayerFileData(NetworkInterface computer) {
		File rpFile = getRemotePlayerFile(computer);
		
		String playerdata = "";
		if(rpFile != null) {
			try {
				BufferedReader br = new BufferedReader(new FileReader(rpFile));
				try {
					String mac = br.readLine(); // this is the mac address.
					String line = "";
					while((line = br.readLine()) != null)
						playerdata += line + "\n"; // should get the right data.
					playerdata = playerdata.substring(0, playerdata.length()-1);
				} catch(IOException ex) {
					System.err.println("failed to read line from file.");
					ex.printStackTrace();
				}
			} catch(FileNotFoundException ex) {
				System.err.println("couldn't find remote player file: " + rpFile);
				ex.printStackTrace();
			}
		}
		
		return playerdata;
	}
	
	public boolean parsePacket(byte[] alldata, InetAddress address, int port) {
		
		MinicraftProtocol.InputType inType = MinicraftProtocol.getInputType(alldata[0]);
		
		byte[] data = Arrays.copyOfRange(alldata, 1, alldata.length);
		NetworkInterface computer = null;
		try {
			computer = NetworkInterface.getByInetAddress(address);
		} catch(SocketException ex) {
			System.err.println("couldn't get network interface from address.");
			ex.printStackTrace();
		}
		/*
			wanted behavior for each input type:
			
			-INVALID: print message to console.
			
			-LOGIN
				expected data: username and game version
				behavior: check version to match game version
					-if match:
						+create remote player
						+find good start pos from game instance's surface level
						+send back INIT_W packet to given address and port
						+send ADD packet to all clients *besides* the one that sent the packet, with remote player data
					-if no match:
						+send back INVALID packet with message that versions didn't match; need ____(current) version
			
			-INIT_W: ignore or send back INVALID: not allowed to set data
			
			-INIT_T
				expected data: level number
				behavior: send back list of tiles for specified level
			
			-INIT_E
				expected data: level number
				behavior: send back list of entities on specified level, enough data to construct them anew
			
			-DISCONNECT
				expected data: none
				behavior: send REMOVE packet to the other clients, data being the id of the remote player matching the address from which the DISCONNECT packet was recieved.
			
			-TILE, ENTITY, ADD, REMOVE, NOTIFY: broadcast the packet to all the other clients
		*/
		
		// handle reports of type INVALID
		if(inType == MinicraftProtocol.InputType.INVALID) {
			String msg = new String(data);
			System.err.println(msg);
			return false;
		}
		
		/// identify the client who sent this packet:
		RemotePlayer sender = getClientPlayer(address, port);
		// the sender should always be null for LOGIN packets, but not for any other. maybe invalid, but that returns above.
		if(sender == null && inType != MinicraftProtocol.InputType.LOGIN && inType != MinicraftProtocol.InputType.USERNAMES) {
			System.err.println("SERVER error: cannot identify sender of packet; type="+inType.name());
			sendError("You must login first.", address, port);
			return false;
		}
		
		if(MinicraftProtocol.InputType.serverOnly.contains(inType)) {
			/// these are ALL illegal for a client to send.
			System.err.println("SERVER warning: client " + sender + " sent illegal packet type " + inType + " to server.");
			//sendError("You cannot set the world variables.", address, port);
			return false;
		}
		
		switch(inType) {
			case USERNAMES:
				String names = "";
				for(RemotePlayer rp: clientList)
					names += rp.username + "\n";
				
				sendData(prependType(MinicraftProtocol.InputType.USERNAMES, names.getBytes()), address, port);
				return true;
			
			case LOGIN:
				String info = new String(data);
				String username = info.split(";")[0];
				Load.Version clientVersion = new Load.Version(info.split(";")[1]);
				// check version; send back invalid if they don't match.
				if(clientVersion.compareTo(new Load.Version(Game.VERSION)) != 0) {
					sendError("wrong game version; need " + Game.VERSION, address, port);
					return false;
				}
				
				/// versions match; make client player
				RemotePlayer clientPlayer = new RemotePlayer(game, username, address, port);
				clientPlayer.findStartPos(Game.levels[Game.lvlIdx(0)]); // find start pos
				
				/// now, we need to check if this player has played in this world before. If they have, then all previoeus settings and items and such will be restored.
				String playerdata = ""; // this stores the data fetched from the files.
				
				if(clientPlayer.ipAddress.isLoopbackAddress() && !(game.player instanceof RemotePlayer)) {
					/// this is the first person on localhost. I believe that a second person will be saved as a loopback address, but a third will simply overwrite the second.
					if (Game.debug) System.out.println("host player found");
					List<String> datalist = new ArrayList<String>();
					Save.writePlayer(game.player, datalist);
					for(String str: datalist)
						playerdata += str + ",";
					playerdata = playerdata.substring(0, playerdata.length()-1) + "\n";
					Save.writeInventory(game.player, datalist);
					for(String str: datalist)
						playerdata += str + ",";
					playerdata = playerdata.substring(0, playerdata.length()-1);
					
					game.player = clientPlayer; // all the important data has been saved.
				}
				else {
					playerdata = getRemotePlayerFileData(computer);
				}
				
				if(playerdata.length() > 0) {
					/// if a save file was found, then send the data to the client so they can resume where they left off.
					sendData(prependType(MinicraftProtocol.InputType.PLAYER, playerdata.getBytes()), sender);
					// and now, initialize the RemotePlayer instance with the data.
					(new Load()).loadPlayer(clientPlayer, Arrays.asList(playerdata.split("\\n")[0]));
					// we really don't need to load the inventory.
				}
				
				// now, we send the INIT_W packet and notify the others clients.
				
				int playerlvl = Game.lvlIdx(clientPlayer.level != null ? clientPlayer.level.depth : 0);
				if(!Game.levels[playerlvl].getEntities().contains(clientPlayer)) // this will be true if their file was already found, since they are added in Load.loadPlayer().
					Game.levels[playerlvl].add(clientPlayer); // add to level (**id is generated here**)
				//making INIT_W packet
				int[] toSend = {
					clientPlayer.eid,
					Game.levels[playerlvl].w,
					Game.levels[playerlvl].h,
					clientPlayer.x,
					clientPlayer.y,
					Game.tickCount,
					ModeMenu.mode,
					game.scoreTime // this will only be used if in score mode.
				};
				String sendString = "";
				for(int val: toSend)
					sendString += val+",";
				/// send client world info
				sendData(prependType(MinicraftProtocol.InputType.INIT_W, sendString.getBytes()), sender);
				clientList.add(clientPlayer); /// file client player
				/// tell others of client joining
				broadcastData(prependType(MinicraftProtocol.InputType.ADD, Save.writeEntity(clientPlayer, false).getBytes()), clientPlayer);
				
				return true;
			
			case INIT_T:
				// send back the tiles in the level specified.
				int levelidx = (int) data[0];
				if(levelidx >= 0 && levelidx < Game.levels.length) {
					byte[] tiledata = new byte[Game.levels[levelidx].tiles.length*2 + 1];
					tiledata[0] = (byte) MinicraftProtocol.InputType.INIT_T.ordinal();
					for(int i = 0; i < tiledata.length/2 - 2; i++) {
						tiledata[i*2 + 1] = Game.levels[levelidx].tiles[i];
						tiledata[i*2+1 + 1] = Game.levels[levelidx].data[i];
					}
					sendData(tiledata, sender);
					return true;
				} else {
					System.err.println("SERVER warning: Client " + sender + " tried to request tiles from nonexistent level " + levelidx);
					sendError("requested level does not exist.", address, port);
					return false;
				}
			
			case INIT_E:
				// send back the entities in the level specified.
				int lvlidx = (int) data[0];
				if(lvlidx >= 0 && lvlidx < Game.levels.length) {
					String edata = "";
					for(Entity e: Game.levels[lvlidx].getEntities()) {
						edata += Save.writeEntity(e, false) + ",";
					}
					edata = edata.substring(0, edata.length()-1); // cut off trailing comma
					
					sendData(prependType(MinicraftProtocol.InputType.INIT_E, edata.getBytes()), sender);
					return true;
				} else {
					System.err.println("SERVER warning: Client " + sender + " tried to request entities from nonexistent level " + lvlidx);
					sendError("requested level does not exist.", address, port);
					return false;
				}
			
			case DISCONNECT:
				/// tell the other clients to remove this client from their games
				broadcastData(prependType(MinicraftProtocol.InputType.REMOVE, String.valueOf(sender.eid).getBytes()), sender);
				clientList.remove(sender); // remove from the server list
				return true;
			
			case SAVE:
				String filedata = new String(data);
				/// save this client's data to a file.
				String filename = ""; // this will hold the path to the file that will be saved to.
				/// first, determine if this is the main player. if not, determine if a file already exists for this client. if not, find an available file name. for simplicity, we will just count the number of remote player files saved.
				
				if(sender == game.player) {
					String[] parts = filedata.split("\\n");
					List<String> datastrs = new ArrayList<String>();
					
					Save save = new Save(sender);
					for(String str: parts[0].split(","))
						datastrs.add(str);
					save.writeToFile(save.location+"Player"+Save.extention, datastrs);
					datastrs.clear();
					for(String str: parts[1].split(","))
						datastrs.add(str);
					save.writeToFile(save.location+"Inventory"+Save.extention, datastrs);
					
					return true;
				}
				
				File rpFile = getRemotePlayerFile(computer);
				if(rpFile.exists())
					filename = rpFile.getName();
				else {
					File[] clientSaves = getRemotePlayerFiles();
					// check if this remote player already has a file.
					int numFiles = clientSaves.length;
					filename = "RemotePlayer"+numFiles+Save.extention;
				}
				
				try {
					filedata = (new String(computer.getHardwareAddress())) + "\n" + filedata;
				} catch(SocketException ex) {
					System.err.println("couldn't get mac address.");
					ex.printStackTrace();
				}
				
				String filepath = worldPath+"/"+filename;
				try {
					java.nio.file.Files.write((new File(filepath)).toPath(), Arrays.asList(filedata.split("\\n")), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
				} catch(IOException ex) {
					System.err.println("problem writing remote player to file " + filepath);
					ex.printStackTrace();
				}
				// the above will hopefully write the data to file.
				return true;
			
			case CHESTIN: case CHESTOUT:
				String[] contents = new String(data).split(";");
				int eid = Integer.parseInt(contents[0]);
				Entity e = Game.getEntity(eid);
				if(e == null || !(e instanceof Chest)) {
					System.err.println("SERVER error with CHESTOUT request: Specified chest entity did not exist or was not a chest.");
					return false;
				}
				Chest chest = (Chest) e;
				
				if(inType == MinicraftProtocol.InputType.CHESTIN) {
					Item item = Items.get(contents[1]);
					if(item == null) {
						System.err.println("SERVER error with CHESTIN request: specified item could not be found from string: " + contents[1]);
						return false;
					}
					chest.inventory.add(item);
				}
				else if(inType == MinicraftProtocol.InputType.CHESTOUT) {
					int index = Integer.parseInt(contents[1]);
					if(index >= chest.inventory.invSize() || index < 0) {
						System.err.println("SERVER error with CHESTOUT request: specified chest inv index is out of bounds: "+index+"; inv size:"+chest.inventory.invSize());
						return false;
					}
					// was a valid index
					Item removed = chest.inventory.remove(index);
					
					sendData(prependType(MinicraftProtocol.InputType.CHESTOUT, removed.getData().getBytes()), sender); // send back the *exact* same packet as was sent; the client will handle it accordingly, by changing their inventory.
				}
				
				broadcastData(prependType(MinicraftProtocol.InputType.ENTITY, (chest.eid+";inventory:"+chest.inventory.getItemData()).getBytes()), sender);
				return true;
			
			case PICKUP:
				Entity entity = Game.getEntity(Integer.parseInt(new String(data)));
				if(entity == null || !(entity instanceof ItemEntity)) {
					System.err.println("SERVER error with PICKUP request: Requested entity does not exist or is not an ItemEntity.");
					return false;
				}
				if(!entity.removed) {
					entity.remove();
					sendData(alldata, sender);
					broadcastData(prependType(MinicraftProtocol.InputType.REMOVE, String.valueOf(entity.eid).getBytes()), sender);
				} else
					return false;
				return true;
			
			case INTERACT:
				//x, y, dir, item
				String[] pinfo = new String(data).split(";");
				// since this should be the most up-to-date data, just update the remote player coords with them.
				sender.x = Integer.parseInt(pinfo[0]);
				sender.y = Integer.parseInt(pinfo[1]);
				sender.dir = Integer.parseInt(pinfo[2]);
				sender.activeItem = Items.get(pinfo[3]); // this can be null; and that's fine, it means a fist. ;)
				int arrowCount = Integer.parseInt(pinfo[4]);
				int curArrows = sender.inventory.count(Items.get("arrow"));
				if(curArrows < arrowCount)
					sender.inventory.add(Items.get("arrow"), arrowCount-curArrows);
				if(curArrows > arrowCount)
					sender.inventory.removeItems(Items.get("arrow"), curArrows-arrowCount);
				sender.attack();
				/// note: the player may have fired an arrow, but we won't tell because that player will update it theirself.
			
			/// I'm this should end up never being used...
			default:
				System.out.println("server used default behavior for input type " + inType);
				broadcastData(alldata, sender);
				return true;
		}
	}
	
	public void broadcastData(byte[] data) {
		for(RemotePlayer client: clientList) {
			sendData(data, client);
		}
	}
	
	public void broadcastData(byte[] data, RemotePlayer sender) {
		for(RemotePlayer client: clientList) {
			if(client != sender) // send this packet to all EXCEPT the specified one.
				sendData(data, client);
		}
	}
	
	public void sendData(byte[] data, RemotePlayer rp) { sendData(data, rp.ipAddress, rp.port); }
	public void sendData(byte[] data, InetAddress ip, int port) {
		DatagramPacket packet = new DatagramPacket(data, data.length, ip, port);
		try {
			socket.send(packet);
		} catch(IOException ex) {
			ex.printStackTrace();
		}
	}
	
	private void sendError(String message, InetAddress ip, int port) {
		sendData(prependType(MinicraftProtocol.InputType.INVALID, message.getBytes()), ip, port);
	}
	
	/*public static byte[] prependType(MinicraftProtocol.InputType type, byte[] data) {
		byte[] fulldata = new byte[data.length+1];
		fulldata[0] = (byte) type.ordinal();
		for(int i = 1; i < fulldata.length; i++)
			fulldata[i] = data[i-1];
		return fulldata;
	}*/
	
	public RemotePlayer getClientPlayer(InetAddress ip, int port) {
		for(RemotePlayer client: clientList)
			if(client.ipAddress.equals(ip) && client.port == port)
				return client;
		
		return null;
	}
	
	public void endConnection() {
		synchronized ("lock") {
			for(RemotePlayer client: clientList) {
				broadcastData(prependType(MinicraftProtocol.InputType.DISCONNECT, (new byte[] {})));
			}
			
			clientList.clear();
		}
		
		try {
			socket.close();
		} catch (NullPointerException ex) {}
	}
	
	public boolean isConnected() {
		return socket != null && !socket.isClosed();
	}
}
