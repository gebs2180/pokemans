---
name: deploy-prod
description: Use when Zach asks to deploy, for deploy commands, or to check prod's health.
---

Zach runs every command that changes the server. Never write to the server, and never read or print `deploy/.env`.

**Check prod, read-only.** From the repo root:

```bash
ssh -o BatchMode=yes deploy@beta.rawmark.app 'bash -s' < deploy/prod-check.sh
```

Pass a window with `'bash -s -- 6h'`. Keep extra queries read-only.

**Before a deploy:**

1. Get the server's commit from the check above and compare it with `origin/main`.
2. List new Flyway migrations, new keys in `deploy/compose.prod.yml` and `deploy/.env.example`, and any `.env` line Zach must add.
3. Remind Zach to warn Discord: logins and uploads fail for 30 to 60 s.

**The deploy.** Give Zach exactly these, run from the home directory:

```bash
ssh deploy@beta.rawmark.app
```

```bash
nohup bash ~/pokemans/deploy/deploy.sh > ~/deploy.log 2>&1 & tail -f ~/deploy.log
```

The first deploy that adds `deploy.sh` needs `cd ~/pokemans && git pull` first.

**After Zach says it finished:** run the check with a window covering the deploy and report it in five lines. If SSH dropped, the script keeps running, so read `~/deploy.log` before suggesting anything else.
