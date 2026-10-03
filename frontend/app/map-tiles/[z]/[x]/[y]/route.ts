import { serveTile } from "@app-shell/mapTiles";

export const dynamic = "force-dynamic";

export async function GET(_req: Request, ctx: { params: Promise<{ z: string; x: string; y: string }> }): Promise<Response> {
  const { z, x, y } = await ctx.params;
  return serveTile(z, x, y);
}
