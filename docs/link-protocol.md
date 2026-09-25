# CraftBridge link protocol

The wire contract between the **CraftBridge Paper plugin** and the optional
**CraftBridge-Client** mod. Both codebases keep the same copy of `link/LinkProtocol.java`
(identical but for the plugin's one `import com.dierks.craftbridge.jei.VarInts;` line) and
of `link/SnapshotTracker.java`; if they ever diverge, the version byte at the head of every
payload makes the pair refuse to talk rather than misread each other.

## Why the mod exists

A vanilla crafting menu has 36 player-inventory slots, and JEI decides craftability on the
*client* by scanning the slots of the open menu. A server on its own therefore cannot show
more than 36 item types — that is the phantom-slot ceiling, and it is not a bug to be fixed
server-side. AE2 hit the same wall and solved it the same way: a client-side JEI plugin that
checks its own network alongside the inventory.

## Channels

| Channel | Direction | Purpose |
|---|---|---|
| `craftbridge:hello` | C→S, then S→C | Announce the mod; the reply switches it on and carries flags (below). The server may send it again at any time to update the flags |
| `craftbridge:storage` | S→C | Full snapshot or delta of everything in range, with a sequence number |
| `craftbridge:resync` | C→S | "I fell behind at sequence N, send me everything" |
| `craftbridge:storage_ack` | C→S | "I have snapshot N and I am (not) drawing it": only this turns the phantom slots off |
| `craftbridge:pull_request` | C→S | The player clicked an item in the storage panel: which item, which click |
| `craftbridge:transfer_request` | C→S | The player clicked `[+]`: this recipe, max-transfer, complete-sets |
| `craftbridge:sort_request` | C→S | The player middle-clicked in a container screen: which menu id, which half |
| `craftbridge:transfer_result` | S→C | Success, or the reason, for a transfer, a pull or a sort |
| `craftbridge:session_end` | S→C | Drop the cached snapshot (close, teleport, death, out of range) |
| `craftbridge:item_catalog` | S→C | Custom items, so JEI can give them their own tiles |

### Hello flags

| Bit | Name | Meaning |
|---|---|---|
| 1 | `FLAG_PHANTOM_SLOTS_OFF` | the server has turned phantom slots off for this player (not set today: the `storage_ack` handshake decides) |
| 2 | `FLAG_SORT` | a middle-click will sort for this player: sorting on, `sorting.middle-click.allowed`, `craftbridge.sort`, and their own toggle. Without it the mod leaves middle-click alone |

A client ignores bits it does not know, so a new flag never needs a version bump; the
server re-sends `hello` whenever a flag changes (a `/sort settings` toggle, a reload).

### `sort_request`

`varint version, varint requestId, varint containerId, string target` where `target` is
`CONTAINER` (the open container's slots) or `PLAYER` (the player's own rows). The server
refuses a `containerId` that is not the menu open right now, then applies `/sort`'s rules;
the answer is a `transfer_result` with the same `requestId`, whose message (possibly empty)
the mod shows in chat.

If no `hello` reply arrives, the mod does nothing at all — a vanilla server, or a server
without CraftBridge, is simply a server where JEI behaves normally.

## Trust

The client says **which recipe it wants** and nothing else. It never says what it has, how
much, or which slots to move. The server validates that the player has a live linked session
in range, that the items are still in the containers, and does the moving itself. A
`transfer_request` carries the storage sequence its craftability check was based on, so a
request made against a stale view is refused with a reason rather than acted on.

## Sizing: snapshot once, then deltas

A real base has hundreds to low thousands of distinct types in range of one workbench, and
the view is resent after every transfer and craft — so the full snapshot goes out once, on
open, and everything after it is a delta of just what changed. A count of zero in a delta
means the type is gone. Every message carries a sequence number one higher than the last;
the client applies deltas strictly in order and asks for a full snapshot the moment it sees
a gap, rather than letting a silently-wrong view offer crafts the server will refuse.

Measured with `LinkProtocol.estimatedBytes` (framing plus the item blobs, which dominate):

| Types in range | 8-byte stacks | 24-byte stacks | 64-byte stacks |
|---|---|---|---|
| 200 | 2.0 KiB | 5.2 KiB | 13.0 KiB |
| 500 | 5.3 KiB | 13.1 KiB | 32.6 KiB |
| 1000 | 10.6 KiB | 26.2 KiB | 65.3 KiB |
| 2000 | 21.4 KiB | 52.6 KiB | 130.7 KiB |

A typical delta — a dozen types changed by a craft — is **316 bytes**.

So **no compression is needed**: even a 2000-type base with fat component-carrying stacks is
131 KiB, an eighth of the 1 MiB custom-payload ceiling, and it is sent once per session
rather than per craft. If a base ever did approach the ceiling the fix is to page the initial
snapshot across several messages, not to compress — the steady state is already sub-kilobyte.

## Item stacks on the wire

Item stacks are opaque length-prefixed blobs, written by the game's own item codec on
whichever side is encoding. That keeps `LinkProtocol` free of both Bukkit and Minecraft
types, so it is pure framing that can be unit tested and shared verbatim between a Paper
plugin and a Minecraft mod.
