import nextEnv from "@next/env";
import { Database } from "../lib/database.ts";
import { migrate } from "../lib/migrate.ts";
import { Service } from "../lib/service.ts";

nextEnv.loadEnvConfig(process.cwd());
const action = process.argv[2];
if (action !== "migrate" && action !== "seed") throw new Error("Use db:migrate or db:seed.");
const url = action === "migrate" ? process.env.DATABASE_URL_UNPOOLED || process.env.DATABASE_URL : process.env.DATABASE_URL;
const db = new Database(url || "");
try {
  if (action === "migrate") await migrate(db);
  else await new Service(db).seed();
  console.log(action === "migrate" ? "Database migrations applied." : "Demo seed initialized; existing records preserved.");
} finally { await db.close(); }
