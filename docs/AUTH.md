# Singularity Auth

Logins for a server that runs with `online_mode = false`. Plugin: `singularity-auth-<version>.jar` in
`SingularityPlugins/`; it needs the Singularity API mod 4.4 or later in `SingularityMods/`.

An offline-mode server believes any name a client gives. This plugin makes every player prove who they are, in one
of two ways, and decides which **before the player enters the world**.

## Premium and cracked players

| | Premium | Cracked |
|---|---|---|
| Who | The name belongs to a paid Minecraft account | Everyone else |
| Proof | Mojang's session servers confirm the client owns the account, exactly as on an online-mode server | A password, registered on the first join |
| Password | None | `/register`, then `/login` on every join |
| UUID and skin | The account's real ones | The offline UUID made from the name; no skin |
| Can be impersonated | No | Only by someone who knows the password |

How a connecting client is sorted (`premium.protect-paid-names = true`, the default):

1. The name has a **premium account** here: the client must pass Mojang.
2. The name has a **password account** here: it stays a password account. Whoever registered a name first keeps it.
3. The name has **no account** yet: the server asks Mojang whether the name belongs to a paid account (only the
   name is sent). Paid: the client must pass Mojang, so only the owner can take the name. Not paid: the client
   comes in as a cracked player and registers a password.
4. Mojang cannot be reached for a name without an account: the client is asked to try again in a moment. Letting it
   in would let it register a password for a name that may belong to somebody.

A cracked client that picks a paid name therefore cannot join under it (it fails Mojang's check) and has to choose
another name. A cracked player who later buys the game runs `/premium confirm` once.

Also refused at the door: names that are not 3 to 16 letters, digits or underscores; a name that is already online
(in offline mode the game would otherwise throw the player who is already there off the server, so anyone could
kick anyone by joining under their name); a registered name in a different spelling (`steve` for `Steve`); a name
or address that is locked after too many wrong passwords.

## Until a cracked player has logged in

The server ignores everything their client sends except what the connection itself needs, the login commands and
the login window. They cannot walk (falling is allowed), chat, run other commands, use, drop or move items, open
anything, break or place blocks, or hit, and they take no damage. This is an allow-list: a packet that is not on it
is dropped, including packets a later Minecraft version adds. Chat is blocked too, so a password typed without the
slash does not end up in everybody's chat. After `login-timeout-seconds` (60) they are kicked.

## Passwords

* Stored as PBKDF2-HMAC-SHA256, 600,000 rounds, a random 16-byte salt each, compared in constant time. The password
  itself is never written anywhere. Hashes made with fewer rounds are upgraded at the next login.
* At least 8 characters, at most 64, not containing the player's name, not one of the usual first guesses.
* 5 wrong passwords: the player is kicked and the name and the address are locked for 5 minutes; each further lock
  is twice as long, up to an hour.
* At most 3 password accounts per address (`limits.accounts-per-address`).
* Hashing runs on two threads of its own (a login costs about a quarter of a second of one core), never on the
  server's tick.

## The account file

`SingularityPlugins/auth/accounts.tsv`, one line per account, rewritten through a temporary file so that a crash
leaves either the old or the new file; the previous version is kept as `accounts.tsv.bak`. If the file is damaged
the plugin refuses to start rather than treat every name as unregistered. Addresses are stored only as a keyed
hash (key in `address.key`): enough to tell "same address as last time", not which address.

It is a file, not a database server: fine for thousands of accounts on one server, not shareable between several
servers. MySQL / SQLite are not supported.

## Commands

| Command | Who | |
|---|---|---|
| `/register <password> <password again>` | cracked | create the account |
| `/login <password>` (`/l`) | cracked | log in; without a password it opens the login window |
| `/logout` | cracked | log out without leaving |
| `/changepassword <old> <new>` | cracked | |
| `/premium confirm` | cracked, logged in | tie the name to your paid account; from the next join Mojang verifies you |
| `/auth info <name>` | operators | kind of account, registered, last login |
| `/auth unregister <name>` | operators | delete an account (the name is free again) |
| `/auth cracked <name>` | operators | undo `/premium` (the old password works again) |
| `/auth forcelogin <player>` | operators | log a waiting player in without a password |
| `/auth stats`, `/auth reload` | operators | |

## The login window

With `login-gui = true` (default) an unauthenticated player gets a window with a password field (two for
registering) and a button; the commands work as well. It is one of the game's own dialogs, so it needs no client
mod. Minecraft has no field that hides typed text: the password is visible on the player's own screen while they
type, as it is in the chat line. `login-gui = false` leaves only the commands.

## What this does not protect against

* **A cracked player's name is only as safe as their password.** Someone who knows or guesses it is that player.
* **Before logging in, a cracked client already sees the world around the place the account was last at, and the
  account's inventory**, because the player is in the world while they type the password. They cannot do anything
  there, but they can look. (Premium players are checked before they enter.)
* **The connection of a cracked player is not encrypted** (Minecraft only encrypts after Mojang's check). A
  password travels as plain text: anyone who can read the player's network traffic (same Wi-Fi, a malicious VPN)
  can read it. Players should not reuse a password that matters elsewhere.
* **Sessions by address** (`limits.session-minutes`, off by default) treat everyone behind one address as the same.
* The plugin trusts Mojang's answer about which names are paid, and the server's operators.

With `online_mode = true` none of this is needed: Mojang checks every player, and the plugin logs everyone in.

## Tested

Unit tests cover hashing, the account file, lockouts and the door's rules. On test servers (26.1, 26.2, 26.3,
offline mode) a raw network client got an encryption request for a paid name, was let in for a free name, and was
turned away for a bad name, a name already online and a wrong spelling. Fake players that send real packets could
do nothing before logging in, registered and logged in by command and by the login window, were refused weak and
mismatched passwords, were locked out at the fifth wrong password and timed out after 60 seconds.

Not tested: a real Minecraft client (so how the login window looks, and a full premium login through Mojang, have
not been seen), and a server with real players.
