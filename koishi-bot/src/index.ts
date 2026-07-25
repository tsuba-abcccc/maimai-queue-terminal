import { Bot, Context, Schema, Session } from "koishi";
import { randomUUID } from "node:crypto";

export const name = "maimai-q";
export const inject = { required: ["database", "http"] };

interface PluginState {
  key: string;
  value: string;
}

type NotificationDeliveryStatus = "PENDING" | "DELIVERED" | "FAILED";

export interface NotificationDelivery {
  queueId: string;
  eventId: string;
  qqNumber: string;
  status: NotificationDeliveryStatus;
  attempts: number;
  nextRetryAt: number;
  lastError: string;
  updatedAt: number;
}

declare module "koishi" {
  interface Tables {
    maimai_q_state: PluginState;
    maimai_q_delivery: NotificationDelivery;
  }
}

type PlayerGender = "MALE" | "FEMALE" | "UNDISCLOSED";
type PlayPreference = "SOLO" | "OPEN_TO_JOIN";
type ProfilePreference = PlayPreference | "ASK_EVERY_TIME";
type QueueOperation =
  | "JOIN_QUEUE"
  | "DEFER_ONE_ROUND"
  | "CANCEL_DEFER_ONE_ROUND"
  | "TEMPORARILY_LEAVE"
  | "CANCEL_TEMPORARY_LEAVE"
  | "TRANSFER_MACHINE"
  | "CHANGE_PLAY_PREFERENCE"
  | "LEAVE_QUEUE";

interface QueueRules {
  allow_defer_one_round: boolean;
  allow_temporary_leave: boolean;
}

interface QueueRegistration {
  registration_id: string;
  display_id: string;
  preference: PlayPreference;
  deferred_once: boolean;
  temporarily_away: boolean;
  temporary_away_skipped_turns: number;
  fixed_pair: boolean;
  no_show_count: number;
  online_registration_pending_check_in?: boolean;
}

interface WaitingPosition {
  index: number;
  estimated_wait_minutes: number | null;
  registrations: QueueRegistration[];
}

interface QueueMachine {
  id: string;
  name: string;
  operational: boolean;
  stop_reason: string | null;
  stop_reason_detail: string | null;
  playing_started_at: number | null;
  playing: QueueRegistration[];
  waiting_positions: WaitingPosition[];
  registration_count?: number;
  new_registration_estimated_wait_minutes?: number | null;
}

interface QueueStatus {
  queue_id: string;
  captured_at: number;
  registration_open: boolean;
  onebot_sync_enabled?: boolean;
  queue_rules?: QueueRules;
  business_hours?: {
    enabled: boolean;
    outside: boolean;
    closing_soon: boolean;
    closing_grace?: boolean;
    closes_at: number | null;
    registration_closes_at?: number | null;
  };
  terminal: { online: boolean };
  machines: Record<string, QueueMachine>;
}

interface BotPlayer {
  registration_id: string;
  profile_id: string;
  qq_number: string;
  display_id: string;
  machine_id: string;
  machine_name?: string;
  machine_operational?: boolean;
  machine_stop_reason?: string | null;
  machine_stop_reason_detail?: string | null;
  playing_started_at?: number | null;
  position: "PLAYING" | "WAITING";
  position_index: number | null;
  estimated_wait_minutes: number | null;
  co_player_display_ids?: string[];
  preference?: PlayPreference;
  fixed_pair?: boolean;
  registration_type?: "TEMPORARY" | "PLAYER_PROFILE";
  last_played_at?: number | null;
  deferred_once: boolean;
  temporarily_away: boolean;
  temporary_away_skipped_turns: number;
  no_show_count: number;
  last_no_show_action_was_defer: boolean;
  online_registration_pending_check_in?: boolean;
}

interface BotPlayersResponse {
  queue_id: string;
  received_at?: number;
  registration_open?: boolean;
  business_hours?: QueueStatus["business_hours"];
  queue_rules?: QueueRules;
  terminal?: {
    online: boolean;
    last_seen_seconds?: number;
  };
  players: BotPlayer[];
}

interface PlayerProfile {
  profile_id: string;
  nickname: string;
  gender: PlayerGender;
  default_preference: ProfilePreference;
  qq_number: string | null;
  usage_count: number;
  updated_at: number;
}

interface ProfilesResponse {
  profiles: PlayerProfile[];
}

interface AffectedPlayer {
  registration_id: string;
  profile_id: string;
  qq_number: string;
}

interface QueueEvent {
  cursor: number;
  event_id: string;
  occurred_at: number;
  machine_id: string | null;
  type: string;
  title: string;
  detail: string;
  operation_source?: string;
  affected_players: AffectedPlayer[];
}

interface EventsResponse {
  queue_id: string;
  events: QueueEvent[];
  next_cursor: number;
  latest_cursor: number;
  has_more: boolean;
}

interface RemoteCommand {
  command_id: string;
  status: "PENDING" | "APPLIED" | "REJECTED";
  result_detail: string | null;
}

interface QueueCommandFields {
  machine_id?: string;
  target_machine_id?: string;
  preference?: PlayPreference;
}

interface EventCursor {
  queueId: string;
  cursor: number;
}

export const NOTIFICATION_RETRY_DELAYS_MS = [
  5_000,
  15_000,
  30_000,
  60_000,
  120_000,
] as const;
export const NOTIFICATION_MAX_ATTEMPTS = NOTIFICATION_RETRY_DELAYS_MS.length +
  1;
const COMMAND_INPUT_TIMEOUT_MS = 60_000;

export const HELP_TEXT = [
  "你好！",
  "可用的命令如下：",
  "",
  " - 加入排队",
  " - 我的排队",
  " - 查看队列",
  " - 我的资料",
  " - 修改资料",
  " - 排队通知",
  "",
  "有关玩家资料的命令，需要在机厅终端创建资料并设置 QQ 后才能使用。",
].join("\n");

export const PROFILE_EDIT_HELP_TEXT = [
  "可用的命令如下：",
  "",
  " - 修改昵称",
  " - 修改性别",
  " - 修改默认偏好",
  "",
  "发送其中一条命令后，请按提示完成修改。",
  "资料修改需要由机厅终端确认。",
].join("\n");

export interface Config {
  apiBase: string;
  botToken: string;
  oneBotSelfId?: string;
  notificationEnabled: boolean;
  notificationIntervalSeconds: number;
  commandWaitSeconds: number;
}

export const Config: Schema<Config> = Schema.object({
  apiBase: Schema.string()
    .role("link")
    .default("https://abcccc.top")
    .description("排队服务后端地址，不包含 /api 路径。"),
  botToken: Schema.string()
    .role("secret")
    .required()
    .description("服务器 QUEUE_BOT_TOKEN，不能与终端同步令牌相同。"),
  oneBotSelfId: Schema.string()
    .description(
      "用于发送私信的 OneBot 机器人 QQ；留空时使用首个在线 OneBot。",
    ),
  notificationEnabled: Schema.boolean()
    .default(true)
    .description("向好友发送本人相关的排队事件通知。"),
  notificationIntervalSeconds: Schema.number()
    .min(2)
    .max(60)
    .step(1)
    .default(5)
    .description("通知轮询间隔。"),
  commandWaitSeconds: Schema.number()
    .min(3)
    .max(60)
    .step(1)
    .default(15)
    .description("资料修改命令等待终端确认的时间。"),
});

export class QueueApi {
  constructor(private ctx: Context, private config: Config) {}

  getQueue(): Promise<QueueStatus> {
    return this.ctx.http.get(this.url("/api/queue-status"));
  }

  getPlayers(qq: string): Promise<BotPlayersResponse> {
    return this.privatePost("/api/queue-bot/players", { qq });
  }

  getProfiles(qq: string): Promise<ProfilesResponse> {
    return this.privatePost("/api/queue-bot/profiles", { qq });
  }

  getEvents(after: number, limit = 100): Promise<EventsResponse> {
    return this.privateGet("/api/queue-bot/events", { after, limit });
  }

  async updateProfile(
    profileId: string,
    actorQq: string,
    update: Partial<
      Pick<PlayerProfile, "nickname" | "gender" | "default_preference">
    >,
  ): Promise<RemoteCommand> {
    const response = await this.ctx.http<RemoteCommand | { error?: string }>(
      "PATCH",
      this.url(`/api/queue-bot/profiles/${encodeURIComponent(profileId)}`),
      {
        data: { request_id: randomUUID(), actor_qq: actorQq, ...update },
        headers: this.privateHeaders(),
        validateStatus: () => true,
      },
    );
    if (response.status >= 400) {
      throw new Error(
        profileUpdateErrorMessage(response.data, response.status),
      );
    }
    return response.data as RemoteCommand;
  }

  async createQueueCommand(
    actorQq: string,
    operation: QueueOperation,
    fields: QueueCommandFields = {},
  ): Promise<RemoteCommand> {
    const data: Record<string, string> = {
      request_id: randomUUID(),
      actor_qq: actorQq,
      operation,
    };
    for (const [key, value] of Object.entries(fields)) {
      if (value !== undefined) data[key] = value;
    }
    const response = await this.ctx.http<RemoteCommand | { error?: string }>(
      "POST",
      this.url("/api/queue-bot/queue-commands"),
      {
        data,
        headers: this.privateHeaders(),
        validateStatus: () => true,
      },
    );
    if (response.status >= 400) {
      throw new Error(profileUpdateErrorMessage(response.data, response.status));
    }
    return response.data as RemoteCommand;
  }

  getCommand(commandId: string): Promise<RemoteCommand> {
    return this.privateGet(
      `/api/queue-bot/commands/${encodeURIComponent(commandId)}`,
    );
  }

  privateGet<T>(
    path: string,
    params?: Record<string, string | number>,
  ): Promise<T> {
    return this.ctx.http.get(this.url(path), {
      headers: this.privateHeaders(),
      params,
    });
  }

  privatePost<T>(path: string, body: Record<string, string>): Promise<T> {
    return this.ctx.http.post(this.url(path), body, {
      headers: this.privateHeaders(),
    });
  }

  privateHeaders(): Record<string, string> {
    return { Authorization: `Bearer ${this.config.botToken}` };
  }

  url(path: string): string {
    return new URL(path, this.config.apiBase).toString();
  }
}

export function apply(ctx: Context, config: Config) {
  const configError = apiBaseValidationError(config.apiBase);
  if (configError) throw new Error(configError);
  const logger = ctx.logger(name);
  const api = new QueueApi(ctx, config);

  ctx.model.extend("maimai_q_state", {
    key: "string",
    value: "text",
  }, { primary: "key" });

  ctx.model.extend("maimai_q_delivery", {
    queueId: "string",
    eventId: "string",
    qqNumber: "string",
    status: "string",
    attempts: "unsigned",
    nextRetryAt: "double",
    lastError: "text",
    updatedAt: "double",
  }, {
    primary: ["queueId", "eventId", "qqNumber"],
    indexes: [["queueId", "eventId"]],
  });

  ctx.command("maimaiq", "现场排队服务")
    .alias("排队")
    .action(() => HELP_TEXT);

  ctx.middleware((session, next) => {
    if (isOnlyBotMention(session)) return HELP_TEXT;
    return next();
  }, true);

  ctx.command("maimaiq.join", "通过 QQ 玩家资料加入排队")
    .alias("加入排队")
    .action(async ({ session }) =>
      withCommandError(() => joinQueueFromBot(api, config, session))
    );

  ctx.command("maimaiq.status", "查询自己的排队状态")
    .alias("我的排队")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const playerResponse = await api.getPlayers(qq);
        const players = playerResponse.players;
        if (players.length) {
          const queue = await api.getQueue().catch(() => undefined);
          const matchingQueue = queue?.queue_id === playerResponse.queue_id
            ? queue
            : undefined;
          return formatOwnQueue(players, matchingQueue, playerResponse);
        }
        const profiles = (await api.getProfiles(qq)).profiles;
        return profiles.length
          ? "你已有玩家资料，但当前没有正在排队的登记。"
          : "当前 QQ 尚未绑定终端中的玩家资料。请先在机厅终端创建资料。";
      })
    );

  ctx.command("maimaiq.status.defer", "将当前登记暂缓一轮")
    .alias("暂缓一轮")
    .action(async ({ session }) =>
      withCommandError(() =>
        changeAbsenceState(
          api,
          config,
          session,
          "DEFER_ONE_ROUND",
        )
      )
    );

  ctx.command("maimaiq.status.defer.cancel", "取消当前登记的暂缓一轮")
    .alias("取消暂缓一轮")
    .action(async ({ session }) =>
      withCommandError(() =>
        changeAbsenceState(
          api,
          config,
          session,
          "CANCEL_DEFER_ONE_ROUND",
        )
      )
    );

  ctx.command("maimaiq.status.away", "将当前登记设为暂时离开")
    .alias("暂时离开")
    .action(async ({ session }) =>
      withCommandError(() =>
        changeAbsenceState(api, config, session, "TEMPORARILY_LEAVE")
      )
    );

  ctx.command("maimaiq.status.away.cancel", "取消当前登记的暂时离开")
    .alias("取消暂时离开")
    .action(async ({ session }) =>
      withCommandError(() =>
        changeAbsenceState(api, config, session, "CANCEL_TEMPORARY_LEAVE")
      )
    );

  ctx.command("maimaiq.status.transfer", "将当前登记切换至其他机台")
    .alias("切换机台")
    .action(async ({ session }) =>
      withCommandError(() => transferQueueMachine(api, config, session))
    );

  ctx.command("maimaiq.status.preference", "修改当前登记的游玩偏好")
    .alias("修改游玩偏好")
    .action(async ({ session }) =>
      withCommandError(() => changeCurrentPreference(api, config, session))
    );

  ctx.command("maimaiq.status.leave", "退出当前排队")
    .alias("退出排队")
    .action(async ({ session }) =>
      withCommandError(() => leaveQueueFromBot(api, config, session))
    );

  ctx.command("maimaiq.queue", "查看完整队列")
    .alias("查看队列")
    .action(async () =>
      withCommandError(async () => {
        const queue = await api.getQueue();
        if (queue.onebot_sync_enabled === false) {
          throw new Error("现场终端已关闭 QQ Bot 联动。");
        }
        return formatQueue(queue);
      })
    );

  ctx.command("maimaiq.profile", "查看自己的玩家资料")
    .alias("我的资料")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const profile = requireSingleProfile(
          (await api.getProfiles(qq)).profiles,
        );
        return formatProfile(profile);
      })
    );
  ctx.command("maimaiq.profile.edit", "打开玩家资料修改菜单")
    .alias("修改资料")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const profiles = (await api.getProfiles(qq)).profiles;

        if (!profiles.length) {
          return "当前 QQ 尚未绑定玩家资料，不能使用资料修改功能。请先在机厅终端创建玩家资料。";
        }

        requireSingleProfile(profiles);
        return PROFILE_EDIT_HELP_TEXT;
      })
    );

  ctx.command("maimaiq.profile.nickname [nickname:text]", "修改玩家资料昵称")
    .alias("修改昵称")
    .action(async ({ session }, nickname) =>
      withCommandError(async () => {
        const value = await resolveProfileCommandInput(
          session,
          nickname,
          "请输入新的昵称。",
        );
        const validationError = nicknameValidationError(value);
        if (validationError) return validationError;
        return submitProfileUpdate(api, config, session, { nickname: value });
      })
    );

  ctx.command("maimaiq.profile.gender [gender:string]", "修改玩家资料性别")
    .alias("修改性别")
    .action(async ({ session }, gender) =>
      withCommandError(async () => {
        const input = await resolveProfileCommandInput(
          session,
          gender,
          "请输入“男”“女”或“不愿透露”。",
        );
        const value = parseGender(input);
        if (!value) return "请输入“男”“女”或“不愿透露”。";
        return submitProfileUpdate(api, config, session, { gender: value });
      })
    );

  ctx.command(
    "maimaiq.profile.preference [preference:text]",
    "修改玩家资料默认游玩偏好",
  )
    .alias("修改默认偏好")
    .action(async ({ session }, preference) =>
      withCommandError(async () => {
        const input = await resolveProfileCommandInput(
          session,
          preference,
          "请输入“单人游玩”“允许他人加入”或“每次询问”。",
        );
        const value = parsePreference(input);
        if (!value) return "请输入“单人游玩”“允许他人加入”或“每次询问”。";
        return submitProfileUpdate(api, config, session, {
          default_preference: value,
        });
      })
    );

  ctx.command("maimaiq.notifications [state:string]", "查看或调整排队通知")
    .alias("排队通知")
    .action(async ({ session }, state) =>
      withCommandError(async () => {
        const qq = requireQqSession(session);
        const profiles = (await api.getProfiles(qq)).profiles;
        if (!profiles.length) {
          return "当前 QQ 尚未绑定玩家资料，不能调整排队通知。请先在机厅终端创建玩家资料。";
        }
        const requested = parseNotificationPreference(state);
        if (state?.trim() && requested === null) {
          return "请输入“开启”或“关闭”，也可以发送“开启排队通知”或“关闭排队通知”。";
        }
        if (requested !== null) {
          await writeNotificationPreference(ctx, config, qq, requested);
          return formatNotificationPreferenceChanged(
            requested,
            config.notificationEnabled,
          );
        }
        const enabled = await readNotificationPreference(ctx, config, qq);
        return formatNotificationPreferenceMenu(
          enabled,
          config.notificationEnabled,
        );
      })
    );

  ctx.command("maimaiq.notifications.enable", "开启排队通知")
    .alias("开启排队通知")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = await requireNotificationProfile(api, session);
        await writeNotificationPreference(ctx, config, qq, true);
        return formatNotificationPreferenceChanged(true, config.notificationEnabled);
      })
    );

  ctx.command("maimaiq.notifications.disable", "关闭排队通知")
    .alias("关闭排队通知")
    .action(async ({ session }) =>
      withCommandError(async () => {
        const qq = await requireNotificationProfile(api, session);
        await writeNotificationPreference(ctx, config, qq, false);
        return formatNotificationPreferenceChanged(false, config.notificationEnabled);
      })
    );

  if (config.notificationEnabled) {
    let polling = false;
    const poll = async () => {
      if (polling) return;
      polling = true;
      try {
        await pollNotifications(ctx, api, config, logger);
      } catch (error) {
        logger.warn("拉取排队通知失败：%s", apiErrorMessage(error));
      } finally {
        polling = false;
      }
    };
    ctx.on("ready", poll);
    ctx.setInterval(poll, config.notificationIntervalSeconds * 1_000);
  }
}

interface CurrentQueueRegistration {
  qq: string;
  response: BotPlayersResponse;
  player: BotPlayer;
}

async function joinQueueFromBot(
  api: QueueApi,
  config: Config,
  session: Session | undefined,
): Promise<string> {
  const qq = requireQqSession(session);
  const [profilesResponse, playersResponse, queue] = await Promise.all([
    api.getProfiles(qq),
    api.getPlayers(qq),
    api.getQueue(),
  ]);
  const profile = requireSingleProfile(profilesResponse.profiles);
  if (playersResponse.players.length) {
    throw new Error("你已经有一份正在排队的登记，不能重复加入。请先发送“我的排队”查看。");
  }
  if (!queue.terminal.online) {
    throw new Error("现场终端暂时离线，暂不能加入排队。");
  }
  if (queue.onebot_sync_enabled === false) {
    throw new Error("现场终端已关闭 QQ Bot 联动。");
  }
  if (!queue.registration_open) {
    throw new Error("现场当前没有使用登记排队，请在现场自然排队。");
  }

  const machines = sortedMachines(queue).filter(machineCanAcceptRegistration);
  if (!machines.length) {
    throw new Error("当前没有可以加入排队的机台。");
  }
  const machineInput = await resolveQueueCommandInput(
    session,
    [
      `你好，${profile.nickname}。`,
      "",
      "请选择要加入排队的机台：",
      "",
      ...machines.map((machine) => ` - ${formatMachineChoice(machine)}`),
    ].join("\n"),
  );
  const machine = parseMachineChoice(machineInput, machines);
  if (!machine) {
    throw new Error("没有找到这个机台。请发送机台字母，例如“A”或“B”。");
  }

  let preference: PlayPreference | undefined;
  if (profile.default_preference === "ASK_EVERY_TIME") {
    const preferenceInput = await resolveQueueCommandInput(
      session,
      [
        "请选择本次游玩偏好：",
        "",
        " - 单人游玩",
        " - 允许他人加入（推荐）",
        "",
        "接受分配的共同游玩，通常可以缩短等待时间。",
      ].join("\n"),
    );
    preference = parsePlayPreference(preferenceInput) ?? undefined;
    if (!preference) {
      throw new Error("请输入“单人游玩”或“允许他人加入”。");
    }
  }

  const command = await submitQueueCommand(
    api,
    config,
    qq,
    "JOIN_QUEUE",
    {
      machine_id: machine.id,
      preference,
    },
  );
  if (command.status === "REJECTED") {
    return command.result_detail || "现场终端拒绝了这次线上登记。";
  }
  if (command.status === "PENDING") {
    return [
      "线上登记已经提交，正在等待现场终端确认。",
      "",
      "确认成功后，到达现场仍需在终端点击自己的登记，再点击“已到场”完成签到。签到前，这份登记不会进入游玩位置。",
    ].join("\n");
  }
  return [
    `已经加入${compactMachineName(machine.name)} 的等待顺序。`,
    "",
    "这是一份线上登记。到达现场后，请在终端点击自己的登记，再点击“已到场”完成签到。签到前，这份登记不会进入游玩位置。",
  ].join("\n");
}

async function changeAbsenceState(
  api: QueueApi,
  config: Config,
  session: Session | undefined,
  operation:
    | "DEFER_ONE_ROUND"
    | "CANCEL_DEFER_ONE_ROUND"
    | "TEMPORARILY_LEAVE"
    | "CANCEL_TEMPORARY_LEAVE",
): Promise<string> {
  const current = await requireCurrentQueueRegistration(api, session);
  const { player, response } = current;
  requireSignedInWaitingRegistration(player);

  if (operation === "DEFER_ONE_ROUND") {
    if (response.queue_rules?.allow_defer_one_round === false) {
      throw new Error("系统规则不允许暂缓一轮。");
    }
    if (player.deferred_once) {
      throw new Error("这份登记已经暂缓一轮；如需恢复，请发送“取消暂缓一轮”。");
    }
    if (player.temporarily_away) {
      throw new Error("请先发送“取消暂时离开”，再设置暂缓一轮。");
    }
  } else if (operation === "CANCEL_DEFER_ONE_ROUND") {
    if (!player.deferred_once) {
      throw new Error("这份登记当前没有暂缓一轮。");
    }
  } else if (operation === "TEMPORARILY_LEAVE") {
    if (response.queue_rules?.allow_temporary_leave === false) {
      throw new Error("系统规则不允许暂时离开。");
    }
    if (player.temporarily_away) {
      throw new Error("这份登记已经暂时离开；返回后请发送“取消暂时离开”。");
    }
    if (player.deferred_once) {
      throw new Error("请先发送“取消暂缓一轮”，再设置暂时离开。");
    }
  } else if (!player.temporarily_away) {
    throw new Error("这份登记当前没有处于暂时离开状态。");
  }

  const messages: Record<typeof operation, string> = {
    DEFER_ONE_ROUND:
      "登记已暂缓一轮。下一次轮到时会跳过这份登记，之后自动恢复；等待顺序保持不变。",
    CANCEL_DEFER_ONE_ROUND:
      "登记已取消暂缓一轮，将按照当前等待顺序正常参与游玩位置分配。",
    TEMPORARILY_LEAVE: [
      "登记已设为暂时离开。",
      "",
      "返回后需要手动发送“取消暂时离开”。在此之前，排队分组会忽略这份登记；连续轮空三次后，第四次仍未返回将退出排队。",
    ].join("\n"),
    CANCEL_TEMPORARY_LEAVE:
      "登记已取消暂时离开，轮空次数已经清零，并将按照当前等待顺序正常参与游玩位置分配。",
  };
  return formatQueueCommandResult(
    await submitQueueCommand(api, config, current.qq, operation),
    messages[operation],
  );
}

async function transferQueueMachine(
  api: QueueApi,
  config: Config,
  session: Session | undefined,
): Promise<string> {
  const current = await requireCurrentQueueRegistration(api, session);
  requireSignedInWaitingRegistration(current.player);
  const queue = await api.getQueue();
  const candidates = sortedMachines(queue).filter((machine) =>
    machine.id !== current.player.machine_id && machineCanAcceptRegistration(machine)
  );
  if (!candidates.length) {
    throw new Error("当前没有其他可以转入的机台。");
  }
  let target = candidates[0];
  if (candidates.length > 1) {
    const input = await resolveQueueCommandInput(
      session,
      [
        "请选择要转入的机台：",
        "",
        ...candidates.map((machine) => ` - ${formatMachineChoice(machine)}`),
      ].join("\n"),
    );
    const selected = parseMachineChoice(input, candidates);
    if (!selected) {
      throw new Error("没有找到这个机台。请发送列表中的机台字母。");
    }
    target = selected;
  }
  await requireQueueConfirmation(
    session,
    [
      `是否将“${current.player.display_id}”转至${compactMachineName(target.name)}？`,
      "",
      "确认后，这份登记会离开当前机台，并进入目标机台的等待顺序末端。",
    ].join("\n"),
    "确认切换机台",
  );
  return formatQueueCommandResult(
    await submitQueueCommand(api, config, current.qq, "TRANSFER_MACHINE", {
      target_machine_id: target.id,
    }),
    `登记已转至${compactMachineName(target.name)} 的等待顺序末端。`,
  );
}

async function changeCurrentPreference(
  api: QueueApi,
  config: Config,
  session: Session | undefined,
): Promise<string> {
  const current = await requireCurrentQueueRegistration(api, session);
  requireSignedInRegistration(current.player);
  const fixedPairNotice = current.player.fixed_pair
    ? ["", "当前登记属于固定组合。修改本次游玩偏好会解除这个固定组合。"]
    : [];
  const input = await resolveQueueCommandInput(
    session,
    [
      "请选择新的本次游玩偏好：",
      "",
      " - 单人游玩",
      " - 允许他人加入",
      ...fixedPairNotice,
      "",
      "这项操作只修改当前登记，不会改变玩家资料中的默认偏好。",
    ].join("\n"),
  );
  const preference = parsePlayPreference(input);
  if (!preference) {
    throw new Error("请输入“单人游玩”或“允许他人加入”。");
  }
  if (!current.player.fixed_pair && current.player.preference === preference) {
    return `这份登记已经是“${queuePreferenceLabel(preference)}”。`;
  }
  return formatQueueCommandResult(
    await submitQueueCommand(
      api,
      config,
      current.qq,
      "CHANGE_PLAY_PREFERENCE",
      { preference },
    ),
    `本次游玩偏好已改为“${queuePreferenceLabel(preference)}”。玩家资料中的默认偏好没有改变。`,
  );
}

async function leaveQueueFromBot(
  api: QueueApi,
  config: Config,
  session: Session | undefined,
): Promise<string> {
  const current = await requireCurrentQueueRegistration(api, session);
  await requireQueueConfirmation(
    session,
    [
      `是否让“${current.player.display_id}”退出排队？`,
      "",
      current.player.online_registration_pending_check_in
        ? "确认后，这份尚未签到的线上登记会被移除。"
        : "确认后，这份登记会从当前队列中移除；继续游玩时需要重新加入排队。",
    ].join("\n"),
    "确认退出排队",
  );
  return formatQueueCommandResult(
    await submitQueueCommand(api, config, current.qq, "LEAVE_QUEUE"),
    "登记已退出排队。",
  );
}

async function requireCurrentQueueRegistration(
  api: QueueApi,
  session: Session | undefined,
): Promise<CurrentQueueRegistration> {
  const qq = requireQqSession(session);
  const response = await api.getPlayers(qq);
  if (!response.players.length) {
    throw new Error("当前没有正在排队的登记。");
  }
  if (response.players.length > 1) {
    throw new Error("当前 QQ 对应多份排队登记，请先联系现场工作人员处理。");
  }
  return { qq, response, player: response.players[0] };
}

function requireSignedInRegistration(player: BotPlayer): void {
  if (player.online_registration_pending_check_in) {
    throw new Error("这份线上登记尚未在现场签到，目前只能退出排队。");
  }
}

function requireSignedInWaitingRegistration(player: BotPlayer): void {
  requireSignedInRegistration(player);
  if (player.position !== "WAITING") {
    throw new Error("这份登记已经处于游玩位置，不能执行这项等待队列操作。");
  }
}

async function submitQueueCommand(
  api: QueueApi,
  config: Config,
  qq: string,
  operation: QueueOperation,
  fields: QueueCommandFields = {},
): Promise<RemoteCommand> {
  let command = await api.createQueueCommand(qq, operation, fields);
  const deadline = Date.now() + config.commandWaitSeconds * 1_000;
  while (command.status === "PENDING" && Date.now() < deadline) {
    await sleep(1_000);
    command = await api.getCommand(command.command_id);
  }
  return command;
}

function formatQueueCommandResult(
  command: RemoteCommand,
  appliedMessage: string,
): string {
  if (command.status === "APPLIED") return appliedMessage;
  if (command.status === "REJECTED") {
    return command.result_detail || "现场终端拒绝了这次排队操作。";
  }
  return "操作已经提交，正在等待现场终端确认。稍后可发送“我的排队”查看结果。";
}

export async function resolveQueueCommandInput(
  session: Session | undefined,
  instruction: string,
): Promise<string> {
  requireQqSession(session);
  if (!session) throw new Error("当前会话不可用，请重新发送命令。");
  await session.send(
    `${instruction}\n\n请在 60 秒内回复；发送“取消”可以结束本次操作。`,
  );
  const reply = (await session.prompt(COMMAND_INPUT_TIMEOUT_MS)).trim();
  if (!reply) {
    throw new Error("等待输入已结束。这次操作没有提交，请重新发送命令。");
  }
  if (reply === "取消") throw new Error("已取消这次操作。");
  return reply;
}

async function requireQueueConfirmation(
  session: Session | undefined,
  explanation: string,
  confirmation: string,
): Promise<void> {
  const reply = await resolveQueueCommandInput(
    session,
    `${explanation}\n\n如需继续，请回复“${confirmation}”。`,
  );
  if (reply !== confirmation) {
    throw new Error(`没有收到“${confirmation}”，这次操作没有执行。`);
  }
}

export function parsePlayPreference(value?: string): PlayPreference | null {
  const parsed = parsePreference(value);
  return parsed === "SOLO" || parsed === "OPEN_TO_JOIN" ? parsed : null;
}

export function parseMachineChoice(
  value: string | undefined,
  machines: QueueMachine[],
): QueueMachine | null {
  const normalized = value?.trim().replace(/\s+/g, "").toUpperCase() ?? "";
  return machines.find((machine) => {
    const id = machine.id.toUpperCase();
    const name = machine.name.replace(/\s+/g, "").toUpperCase();
    return normalized === id || normalized === `机台${id}` || normalized === name;
  }) ?? null;
}

function sortedMachines(queue: QueueStatus): QueueMachine[] {
  return Object.values(queue.machines).sort((left, right) =>
    left.id.localeCompare(right.id, "zh-CN")
  );
}

export function machineCanAcceptRegistration(machine: QueueMachine): boolean {
  const registrationCount = typeof machine.registration_count === "number"
    ? machine.registration_count
    : machine.playing.length + machine.waiting_positions.reduce(
      (total, position) => total + position.registrations.length,
      0,
    );
  return machine.operational && registrationCount < 20;
}

function formatMachineChoice(machine: QueueMachine): string {
  const estimate = typeof machine.new_registration_estimated_wait_minutes === "number"
    ? machine.new_registration_estimated_wait_minutes <= 0
      ? "（预计很快可以游玩）"
      : `（约 ${machine.new_registration_estimated_wait_minutes} 分钟后）`
    : "";
  return `${compactMachineName(machine.name)}${estimate}`;
}

function compactMachineName(value: string): string {
  return value.replace(/\s*·\s*/g, "·");
}

export async function resolveProfileCommandInput(
  session: Session | undefined,
  suppliedValue: string | undefined,
  instruction: string,
): Promise<string> {
  requireQqSession(session);
  const inlineValue = suppliedValue?.trim();
  if (inlineValue) return inlineValue;
  if (!session) throw new Error("当前会话不可用，请重新发送命令。");

  await session.send(
    `${instruction}\n请在 60 秒内回复；发送“取消”可结束本次修改。`,
  );
  const reply = (await session.prompt(COMMAND_INPUT_TIMEOUT_MS)).trim();
  if (!reply) {
    throw new Error("等待输入已结束。这次修改没有提交，请重新发送命令。");
  }
  if (reply === "取消") throw new Error("已取消这次修改。");
  return reply;
}

async function submitProfileUpdate(
  api: QueueApi,
  config: Config,
  session: Session | undefined,
  update: Partial<
    Pick<PlayerProfile, "nickname" | "gender" | "default_preference">
  >,
): Promise<string> {
  const qq = requireQqSession(session);
  const profile = requireSingleProfile((await api.getProfiles(qq)).profiles);
  let command = await api.updateProfile(profile.profile_id, qq, update);
  const deadline = Date.now() + config.commandWaitSeconds * 1_000;
  while (command.status === "PENDING" && Date.now() < deadline) {
    await sleep(1_000);
    command = await api.getCommand(command.command_id);
  }
  const preferenceNote = update.default_preference
    ? "\n默认游玩偏好只用于以后加入排队，不会改变当前登记的本次偏好。"
    : "";
  if (command.status === "APPLIED") {
    return `玩家资料已经由机厅终端确认并更新。${preferenceNote}`;
  }
  if (command.status === "REJECTED") {
    return command.result_detail || "机厅终端拒绝了这次资料修改。";
  }
  return `修改已经提交，正在等待机厅终端确认。稍后可使用“我的资料”查看结果。${preferenceNote}`;
}

export async function pollNotifications(
  ctx: Context,
  api: QueueApi,
  config: Config,
  logger: ReturnType<Context["logger"]>,
) {
  const stateKey = cursorStateKey(config);
  const saved = await readCursor(ctx, stateKey);
  const firstPage = await api.getEvents(saved?.cursor ?? 0);
  if (!saved) {
    await ctx.database.remove("maimai_q_delivery", {
      queueId: firstPage.queue_id,
    });
    await writeCursor(ctx, stateKey, {
      queueId: firstPage.queue_id,
      cursor: firstPage.latest_cursor,
    });
    return;
  }
  if (saved.queueId !== firstPage.queue_id) {
    await ctx.database.remove("maimai_q_delivery", {
      queueId: saved.queueId,
    });
    await ctx.database.remove("maimai_q_delivery", {
      queueId: firstPage.queue_id,
    });
    await writeCursor(ctx, stateKey, {
      queueId: firstPage.queue_id,
      cursor: saved.cursor,
    });
  }

  let page = firstPage;
  let cursorBlocked = false;
  let selectedBot = selectOneBot(ctx, config.oneBotSelfId);
  const logState = { unavailableBotLogged: false };
  while (true) {
    for (const event of page.events) {
      const complete = await processNotificationEvent(
        ctx,
        api,
        logger,
        page.queue_id,
        event,
        selectedBot,
        logState,
        config,
      );
      if (!complete) {
        cursorBlocked = true;
        selectedBot = selectOneBot(ctx, config.oneBotSelfId);
        continue;
      }
      if (cursorBlocked) continue;

      await writeCursor(ctx, stateKey, {
        queueId: page.queue_id,
        cursor: event.cursor,
      });
      await ctx.database.remove("maimai_q_delivery", {
        queueId: page.queue_id,
        eventId: event.event_id,
        status: "DELIVERED",
      });
    }
    if (!page.has_more) break;
    const previousPageCursor = page.next_cursor;
    page = await api.getEvents(page.next_cursor);
    if (page.next_cursor <= previousPageCursor && page.has_more) {
      logger.warn("排队通知游标没有继续向前，已暂停本轮读取。");
      break;
    }
  }
}

async function processNotificationEvent(
  ctx: Context,
  api: QueueApi,
  logger: ReturnType<Context["logger"]>,
  queueId: string,
  event: QueueEvent,
  bot: Bot | undefined,
  logState: { unavailableBotLogged: boolean },
  config: Config,
): Promise<boolean> {
  const currentRecipients = [
    ...new Set(
      event.affected_players.map((player) => player.qq_number),
    ),
  ];
  if (!currentRecipients.length) return true;

  let deliveries = await ctx.database.get("maimai_q_delivery", {
    queueId,
    eventId: event.event_id,
  });
  if (!deliveries.length) {
    const recipientPreferences = await Promise.all(
      currentRecipients.map(async (qqNumber) => ({
        qqNumber,
        enabled: await readNotificationPreference(ctx, config, qqNumber),
      })),
    );
    const enabledRecipients = recipientPreferences
      .filter((preference) => preference.enabled)
      .map((preference) => preference.qqNumber);
    if (!enabledRecipients.length) return true;
    const now = Date.now();
    await ctx.database.upsert(
      "maimai_q_delivery",
      enabledRecipients.map((qqNumber) => ({
        queueId,
        eventId: event.event_id,
        qqNumber,
        status: "PENDING" as const,
        attempts: 0,
        nextRetryAt: 0,
        lastError: "",
        updatedAt: now,
      })),
    );
    deliveries = await ctx.database.get("maimai_q_delivery", {
      queueId,
      eventId: event.event_id,
    });
  }

  for (const delivery of deliveries) {
    if (isNotificationDeliveryTerminal(delivery)) continue;
    if (!await readNotificationPreference(ctx, config, delivery.qqNumber)) {
      await writeNotificationDelivery(ctx, {
        ...delivery,
        status: "DELIVERED",
        nextRetryAt: 0,
        lastError: "",
        updatedAt: Date.now(),
      });
      continue;
    }
    const now = Date.now();
    if (!isNotificationDeliveryDue(delivery, now)) continue;

    const attempt = delivery.attempts + 1;
    try {
      if (!bot) {
        if (!logState.unavailableBotLogged) {
          logger.warn(
            "没有可用于发送私信的 OneBot 实例，未完成通知将按退避策略重试。",
          );
          logState.unavailableBotLogged = true;
        }
        throw new Error("当前没有可用的 OneBot 实例");
      }
      const current = await api.getPlayers(delivery.qqNumber);
      const status = current.players.length
        ? `\n\n${formatOwnQueue(current.players, undefined, current)}`
        : "";
      await bot.sendPrivateMessage(
        delivery.qqNumber,
        formatQueueNotification(event, status),
      );
      await writeNotificationDelivery(ctx, {
        ...delivery,
        status: "DELIVERED",
        attempts: attempt,
        nextRetryAt: 0,
        lastError: "",
        updatedAt: Date.now(),
      });
    } catch (error) {
      const safeError = redactQqNumber(
        apiErrorMessage(error),
        delivery.qqNumber,
      );
      const failure = nextNotificationFailure(delivery, Date.now(), safeError);
      await writeNotificationDelivery(ctx, failure);
      if (failure.status === "FAILED") {
        logger.warn(
          "排队通知已停止重试：事件 %s，QQ %s，共尝试 %d 次：%s",
          event.event_id,
          maskQqNumber(delivery.qqNumber),
          failure.attempts,
          failure.lastError,
        );
      } else {
        logger.debug(
          "排队通知发送失败，将重试：事件 %s，QQ %s，第 %d 次：%s",
          event.event_id,
          maskQqNumber(delivery.qqNumber),
          failure.attempts,
          failure.lastError,
        );
      }
    }
  }

  const updated = await ctx.database.get("maimai_q_delivery", {
    queueId,
    eventId: event.event_id,
  });
  return updated.length > 0 && updated.every(isNotificationDeliveryTerminal);
}

async function writeNotificationDelivery(
  ctx: Context,
  delivery: NotificationDelivery,
) {
  await ctx.database.upsert("maimai_q_delivery", [delivery]);
}

export function isNotificationDeliveryDue(
  delivery: NotificationDelivery,
  now: number,
): boolean {
  return delivery.status === "PENDING" && delivery.nextRetryAt <= now;
}

export function isNotificationDeliveryTerminal(
  delivery: NotificationDelivery,
): boolean {
  return delivery.status === "DELIVERED" || delivery.status === "FAILED";
}

export function nextNotificationFailure(
  delivery: NotificationDelivery,
  now: number,
  error: string,
): NotificationDelivery {
  const attempts = delivery.attempts + 1;
  const failed = attempts >= NOTIFICATION_MAX_ATTEMPTS;
  const delayIndex = Math.min(
    attempts - 1,
    NOTIFICATION_RETRY_DELAYS_MS.length - 1,
  );
  return {
    ...delivery,
    status: failed ? "FAILED" : "PENDING",
    attempts,
    nextRetryAt: failed ? 0 : now + NOTIFICATION_RETRY_DELAYS_MS[delayIndex],
    lastError: error.slice(0, 500),
    updatedAt: now,
  };
}

function maskQqNumber(qqNumber: string): string {
  if (qqNumber.length <= 4) return "****";
  return `${qqNumber.slice(0, 2)}${"*".repeat(qqNumber.length - 4)}${
    qqNumber.slice(-2)
  }`;
}

export function redactQqNumber(value: string, qqNumber: string): string {
  if (!qqNumber) return value;
  return value.split(qqNumber).join(maskQqNumber(qqNumber));
}

function selectOneBot(ctx: Context, selfId?: string): Bot | undefined {
  return ctx.bots.find((bot) =>
    bot.platform === "onebot" &&
    bot.isActive &&
    (!selfId || bot.selfId === selfId)
  );
}

function cursorStateKey(config: Config): string {
  return `event-cursor:${new URL(config.apiBase).origin}`;
}

async function readCursor(
  ctx: Context,
  key: string,
): Promise<EventCursor | null> {
  const rows = await ctx.database.get("maimai_q_state", { key });
  if (!rows.length) return null;
  try {
    return JSON.parse(rows[0].value) as EventCursor;
  } catch {
    return null;
  }
}

async function writeCursor(ctx: Context, key: string, cursor: EventCursor) {
  await ctx.database.upsert("maimai_q_state", [{
    key,
    value: JSON.stringify(cursor),
  }]);
}

function notificationPreferenceStateKey(config: Config, qqNumber: string): string {
  return `notification-preference:${new URL(config.apiBase).origin}:${qqNumber}`;
}

export async function readNotificationPreference(
  ctx: Context,
  config: Config,
  qqNumber: string,
): Promise<boolean> {
  const rows = await ctx.database.get("maimai_q_state", {
    key: notificationPreferenceStateKey(config, qqNumber),
  });
  return rows[0]?.value !== "disabled";
}

export async function writeNotificationPreference(
  ctx: Context,
  config: Config,
  qqNumber: string,
  enabled: boolean,
) {
  await ctx.database.upsert("maimai_q_state", [{
    key: notificationPreferenceStateKey(config, qqNumber),
    value: enabled ? "enabled" : "disabled",
  }]);
}

export function parseNotificationPreference(value?: string): boolean | null {
  switch (value?.trim()) {
    case "开启":
    case "打开":
    case "启用":
      return true;
    case "关闭":
    case "停用":
      return false;
    default:
      return null;
  }
}

function formatNotificationPreferenceMenu(
  enabled: boolean,
  systemEnabled: boolean,
): string {
  return [
    "排队通知",
    "",
    `个人设置：${enabled ? "已开启" : "已关闭"}`,
    `系统通知：${systemEnabled ? "正在运行" : "暂未启用"}`,
    "",
    "开启后，与你有关的排队变动会通过 QQ 私聊发送。",
    "",
    " - 开启排队通知",
    " - 关闭排队通知",
  ].join("\n");
}

function formatNotificationPreferenceChanged(
  enabled: boolean,
  systemEnabled: boolean,
): string {
  if (!enabled) {
    return "排队通知已关闭。\n\n之后发生的个人排队变动不会再通过私聊发送；你仍可随时重新开启。";
  }
  return systemEnabled
    ? "排队通知已开启。\n\n之后发生的个人排队变动会通过私聊发送。"
    : "排队通知的个人设置已开启。\n\n系统通知目前暂未启用；系统恢复后，将按照这项设置发送。";
}

async function requireNotificationProfile(
  api: QueueApi,
  session: Session | undefined,
): Promise<string> {
  const qq = requireQqSession(session);
  if (!(await api.getProfiles(qq)).profiles.length) {
    throw new Error(
      "当前 QQ 尚未绑定玩家资料，不能调整排队通知。请先在机厅终端创建玩家资料。",
    );
  }
  return qq;
}

export function isOnlyBotMention(
  session: Pick<Session, "platform" | "selfId" | "elements">,
): boolean {
  if (session.platform !== "onebot" || !session.selfId) return false;
  const meaningfulElements = (session.elements ?? []).filter((element) => {
    if (element.type !== "text") return true;
    return String(element.attrs.content ?? "").trim().length > 0;
  });
  if (meaningfulElements.length !== 1) return false;
  const mention = meaningfulElements[0];
  return mention.type === "at" && String(mention.attrs.id ?? "") === session.selfId;
}

export function formatQueue(queue: QueueStatus): string {
  const terminalStatus = queue.terminal.online ? "终端在线" : "终端离线";
  const outsideBusinessHours = queue.business_hours?.enabled &&
    queue.business_hours.outside;
  const queueMode = outsideBusinessHours
    ? "·不在营业时间"
    : queue.registration_open
    ? ""
    : "·自然排队";
  const lines = [`当前队列·${terminalStatus}${queueMode}`];
  if (queue.terminal.online) {
    lines.push("");
  } else {
    lines.push("以下为最近一次同步状态。", "");
  }
  if (queue.business_hours?.closing_grace) {
    lines.push(
      "今日营业时间已结束",
      "不再接收新登记。现有队列处理完毕后将关闭，最迟保留 20 分钟。",
      "",
    );
  } else if (queue.business_hours?.enabled && queue.business_hours.closing_soon) {
    lines.push(
      "将在 30 分钟内闭店",
      "请留意后续队列安排。",
      "",
    );
  }
  for (const machine of sortedMachines(queue)) {
    lines.push(formatMachine(machine), "");
  }
  return lines.join("\n").trimEnd();
}

function formatMachine(machine: QueueMachine): string {
  const waitingCount = machine.waiting_positions.reduce(
    (total, position) => total + position.registrations.length,
    0,
  );
  const registrationCount = machine.playing.length + waitingCount;
  const machineName = compactMachineName(machine.name);
  const overview = [`【${machineName}】`];
  if (!machine.operational) {
    overview.push(
      `停止使用${
        machine.stop_reason
          ? `·${stopReason(machine.stop_reason, machine.stop_reason_detail)}`
          : ""
      }`,
    );
  }
  if (registrationCount) {
    overview.push(
      `${machine.waiting_positions.length} 个等待位置·${registrationCount} 个登记`,
    );
  }
  if (!machine.operational) {
    if (registrationCount) {
      overview.push("登记顺序已保留，恢复使用后继续排队。");
    }
  }

  const sections = [overview];
  if (machine.playing.length) {
    const elapsed = machine.operational && machine.playing_started_at
      ? Math.max(
        0,
        Math.floor((Date.now() - machine.playing_started_at) / 60_000),
      )
      : null;
    const playingState = machine.operational
      ? elapsed === null ? "" : `·${elapsed} 分钟`
      : "·已暂停";
    sections.push(
      [
        `游玩位置 ${machine.id}${playingState}`,
        ...formatQueueRegistrations(machine.playing),
      ],
    );
  } else {
    sections.push([`游玩位置 ${machine.id}·空闲`]);
  }
  for (const position of machine.waiting_positions) {
    const estimate = machine.operational &&
        position.estimated_wait_minutes !== null
      ? position.estimated_wait_minutes <= 0
        ? "·即将游玩"
        : `·约 ${position.estimated_wait_minutes} 分钟后`
      : "";
    sections.push(
      [
        `位置 ${machine.id}${position.index}${estimate}`,
        ...formatQueueRegistrations(position.registrations),
      ],
    );
  }
  return sections.map((section) => section.join("\n")).join("\n\n");
}

export function formatOwnQueue(
  players: BotPlayer[],
  queue?: QueueStatus,
  personalSnapshot?: Pick<
    BotPlayersResponse,
    "terminal" | "registration_open" | "business_hours" | "queue_rules"
  >,
): string {
  if (!players.length) return "当前没有正在排队的登记。";
  const status = players.map((player) => {
    const machine = queue?.machines[player.machine_id];
    const registration = machine
      ? [
        ...machine.playing,
        ...machine.waiting_positions.flatMap((position) =>
          position.registrations
        ),
      ]
        .find((item) => item.registration_id === player.registration_id)
      : undefined;
    const machineOperational = machine?.operational ??
      player.machine_operational;
    const machineName = compactMachineName(
      machine?.name ?? player.machine_name ?? `机台 ${player.machine_id}`
    );
    const playingStartedAt = machine
      ? machine.playing_started_at
      : player.playing_started_at;
    const elapsed = player.position === "PLAYING" &&
        machineOperational !== false && playingStartedAt
      ? Math.max(
        0,
        Math.floor((Date.now() - playingStartedAt) / 60_000),
      )
      : null;
    const location = player.position === "PLAYING"
      ? machineOperational === false
        ? `位于游玩位置 ${player.machine_id}`
        : `正在游玩位置 ${player.machine_id}${
          elapsed === null ? "" : `，已游玩 ${elapsed} 分钟`
        }`
      : `位于队列位置 ${player.machine_id}${player.position_index}`;
    const estimatedWaitMinutes = typeof player.estimated_wait_minutes === "number" &&
        Number.isFinite(player.estimated_wait_minutes)
      ? Math.max(0, Math.trunc(player.estimated_wait_minutes))
      : null;
    const estimate = player.position === "WAITING" &&
        !player.online_registration_pending_check_in &&
        machineOperational !== false &&
        estimatedWaitMinutes !== null
      ? estimatedWaitMinutes <= 0
        ? "，预计现在可以游玩"
        : `，约 ${estimatedWaitMinutes} 分钟后可以游玩`
      : "";
    const machineStopReason = machine
      ? machine.stop_reason
      : player.machine_stop_reason;
    const machineStopReasonDetail = machine
      ? machine.stop_reason_detail
      : player.machine_stop_reason_detail;
    const machineState = machineOperational === false
      ? `${machineName} 已停止使用${
        machineStopReason
          ? `·${stopReason(machineStopReason, machineStopReasonDetail)}`
          : ""
      }，登记顺序已保留。`
      : null;
    const states: string[] = [];
    if (player.online_registration_pending_check_in) {
      states.push("线上登记·待签到");
    }
    if (player.deferred_once) states.push("暂缓一轮");
    if (player.temporarily_away) {
      states.push(
        `暂时离开${
          player.temporary_away_skipped_turns
            ? `·已轮空 ${player.temporary_away_skipped_turns} 次`
            : ""
        }`,
      );
    }
    if (player.no_show_count) {
      const lastAction = player.last_no_show_action_was_defer
        ? "上次处理：暂缓一轮"
        : "上次处理：移至队尾";
      states.push(`未到场记录 ${player.no_show_count} 次·${lastAction}`);
    }
    const currentPreference = registration
      ? queueRegistrationPreferenceLabel(registration)
      : player.preference
      ? queueRegistrationPreferenceLabel({
        preference: player.preference,
        fixed_pair: player.fixed_pair ?? false,
      })
      : null;
    const positionRegistrations = machine
      ? player.position === "PLAYING"
        ? machine.playing
        : machine.waiting_positions.find((position) =>
          position.index === player.position_index
        )?.registrations
      : undefined;
    const coPlayerDisplayIds = positionRegistrations
      ? positionRegistrations
        .filter((item) => item.registration_id !== player.registration_id)
        .map((item) => item.display_id)
      : player.co_player_display_ids ?? [];
    const subject = players.length === 1 ? "你" : `${player.display_id}：`;
    const positionSentence = player.online_registration_pending_check_in &&
        player.position === "WAITING"
      ? `${subject}${location}，签到后可以估算等待时间。`
      : `${subject}${location}${estimate}。`;
    const lines = [
      `${positionSentence}${
        currentPreference ? `游玩偏好：${currentPreference}。` : ""
      }`,
    ];
    if (machineState) lines.push(`机台状态：${machineState}`);
    if (coPlayerDisplayIds.length) {
      lines.push(`共同游玩：${coPlayerDisplayIds.join("、")}。`);
    }
    if (states.length) lines.push(`当前状态：${states.join("；")}。`);
    return lines.join("\n");
  }).join("\n\n");
  const terminalOnline = queue?.terminal.online ??
    personalSnapshot?.terminal?.online;
  const businessHours = queue?.business_hours ?? personalSnapshot?.business_hours;
  const registrationOpen = queue?.registration_open ??
    personalSnapshot?.registration_open;
  const queueRules = queue?.queue_rules ?? personalSnapshot?.queue_rules;
  const notices: string[] = [];
  if (terminalOnline === false) {
    notices.push("终端暂时离线，以下为最近一次同步状态。");
  }
  if (businessHours?.enabled && businessHours.outside) {
    notices.push(
      businessHours.closing_grace
        ? "今日营业时间已结束，现有队列正在收尾。"
        : "当前不在营业时间。",
    );
  } else {
    if (businessHours?.enabled && businessHours.closing_soon) {
      notices.push("将在 30 分钟内闭店，请留意后续队列安排。");
    }
    if (registrationOpen === false) {
      notices.push("当前采用现场自然排队。");
    }
  }
  const blocks = [`你好，${players[0].display_id}。`];
  if (notices.length) blocks.push(notices.join("\n"));
  blocks.push(status);
  if (players.length === 1) {
    const actions = formatOwnQueueActions(players[0], queueRules);
    if (actions.length) blocks.push(actions.map((action) => ` - ${action}`).join("\n"));
  }
  return blocks.join("\n\n");
}

export function formatOwnQueueActions(
  player: BotPlayer,
  queueRules?: QueueRules,
): string[] {
  if (player.online_registration_pending_check_in) return ["退出排队"];
  const actions: string[] = [];
  if (player.position === "WAITING") {
    if (player.deferred_once) {
      actions.push("取消暂缓一轮");
    } else if (player.temporarily_away) {
      actions.push("取消暂时离开");
    } else {
      if (queueRules?.allow_defer_one_round !== false) actions.push("暂缓一轮");
      if (queueRules?.allow_temporary_leave !== false) actions.push("暂时离开");
    }
    actions.push("切换机台");
  }
  actions.push("修改游玩偏好", "退出排队");
  return actions;
}

export function formatQueueNotification(
  event: Pick<QueueEvent, "title" | "detail">,
  status = "",
): string {
  return compactMiddleDots(
    `【排队通知】\n\n${event.title}\n${event.detail}${status}`,
  );
}

function compactMiddleDots(value: string): string {
  return value.replace(/\s*·\s*/g, "·");
}

function formatProfile(profile: PlayerProfile): string {
  return [
    `玩家资料：${profile.nickname}`,
    `性别：${genderLabel(profile.gender)}`,
    `默认偏好：${preferenceLabel(profile.default_preference)}`,
    `使用次数：${profile.usage_count}`,
    "",
    "默认偏好只用于以后加入排队，不会改变当前登记的本次偏好。",
  ].join("\n");
}

function formatQueueRegistrations(
  registrations: QueueRegistration[],
): string[] {
  return registrations.length
    ? registrations.flatMap(formatQueueRegistration)
    : ["暂无登记"];
}

function formatQueueRegistration(registration: QueueRegistration): string[] {
  const lines = [
    ` - ${registration.display_id} (${
      compactQueuePreferenceLabel(registration)
    })`,
  ];
  if (registration.online_registration_pending_check_in) {
    lines.push("    - 线上登记·待签到");
  }
  if (registration.deferred_once) lines.push("    - 暂缓一轮");
  if (registration.temporarily_away) {
    lines.push(
      `    - 暂时离开${
        registration.temporary_away_skipped_turns
          ? `·已轮空 ${registration.temporary_away_skipped_turns} 次`
          : ""
      }`,
    );
  }
  if (registration.no_show_count) {
    lines.push(`    - 未到场记录 ${registration.no_show_count} 次`);
  }
  return lines;
}

function compactQueuePreferenceLabel(
  registration: QueueRegistration,
): string {
  if (registration.fixed_pair) return "固定组合";
  return registration.preference === "SOLO" ? "单人" : "允许加入";
}

function queuePreferenceLabel(value: QueueRegistration["preference"]): string {
  return value === "SOLO" ? "单人游玩" : "允许他人加入";
}

function queueRegistrationPreferenceLabel(
  registration: Pick<QueueRegistration, "preference" | "fixed_pair">,
): string {
  return registration.fixed_pair
    ? "与朋友共同游玩"
    : queuePreferenceLabel(registration.preference);
}

export function nicknameValidationError(value?: string): string | null {
  const nickname = value?.trim() ?? "";
  if (!nickname || [...nickname].length > 18) {
    return "昵称应为 1 至 18 个字符。";
  }
  if (/[\p{Cc}\p{Zl}\p{Zp}]/u.test(nickname)) {
    return "昵称不能包含换行或控制字符。";
  }
  return null;
}

export function parseGender(value?: string): PlayerGender | null {
  switch (value?.trim()) {
    case "男":
      return "MALE";
    case "女":
      return "FEMALE";
    case "不愿透露":
    case "不透露":
      return "UNDISCLOSED";
    default:
      return null;
  }
}

export function parsePreference(value?: string): ProfilePreference | null {
  switch (value?.trim()) {
    case "单人":
    case "单人游玩":
      return "SOLO";
    case "允许加入":
    case "允许他人加入":
      return "OPEN_TO_JOIN";
    case "每次询问":
      return "ASK_EVERY_TIME";
    default:
      return null;
  }
}

function genderLabel(value: PlayerGender): string {
  return { MALE: "男", FEMALE: "女", UNDISCLOSED: "不愿透露" }[value];
}

function preferenceLabel(value: ProfilePreference): string {
  return {
    SOLO: "单人游玩",
    OPEN_TO_JOIN: "允许他人加入",
    ASK_EVERY_TIME: "每次询问",
  }[value];
}

function stopReason(value: string, detail?: string | null): string {
  if (value === "OTHER" && detail?.trim()) {
    return `其他原因（${detail.trim()}）`;
  }
  return {
    NOT_POWERED_ON: "机台未开机",
    NETWORK_DISCONNECTED: "机台断网",
    MAINTENANCE: "机台维护",
    OTHER: "其他原因",
  }[value] || "原因未注明";
}

export function requireQqSession(session: Session | undefined): string {
  if (!session) throw new Error("无法读取当前 QQ 会话。");
  const qq = session.userId;
  if (session.platform !== "onebot" || !qq || !/^[0-9]{5,12}$/.test(qq)) {
    throw new Error("这个功能只能由 OneBot QQ 用户使用。");
  }
  if (!session.isDirect) {
    throw new Error("为保护个人信息，请私聊机器人使用这个功能。");
  }
  return qq;
}

export function apiBaseValidationError(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return "apiBase 必须是有效的服务器地址。";
  }
  if (url.username || url.password) return "apiBase 不能包含用户名或密码。";
  if (url.pathname !== "/" || url.search || url.hash) {
    return "apiBase 应只填写站点根地址，不要包含路径、查询参数或片段。";
  }
  if (url.protocol === "https:") return null;
  const localHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
  if (url.protocol === "http:" && localHosts.has(url.hostname.toLowerCase())) {
    return null;
  }
  return "apiBase 必须使用 HTTPS；只有 localhost、127.0.0.1 或 ::1 可以使用 HTTP。";
}

function requireSingleProfile(profiles: PlayerProfile[]): PlayerProfile {
  if (!profiles.length) throw new Error("当前 QQ 尚未绑定终端中的玩家资料。");
  if (profiles.length > 1) {
    throw new Error("当前 QQ 绑定了多份资料，请先联系现场工作人员处理。");
  }
  return profiles[0];
}

async function withCommandError(
  action: () => Promise<string>,
): Promise<string> {
  try {
    return await action();
  } catch (error) {
    return apiErrorMessage(error);
  }
}

function apiErrorMessage(error: unknown): string {
  const candidate = error as {
    message?: string;
    response?: { data?: { error?: string } };
  };
  return candidate.response?.data?.error || candidate.message ||
    "暂时无法连接排队服务。";
}

export function profileUpdateErrorMessage(
  data: unknown,
  status: number,
): string {
  if (data && typeof data === "object" && "error" in data) {
    const detail = (data as { error?: unknown }).error;
    if (typeof detail === "string" && detail.trim()) return detail.trim();
  }
  if (typeof data === "string" && data.trim()) {
    try {
      return profileUpdateErrorMessage(JSON.parse(data), status);
    } catch {
      const detail = data.trim();
      if (!detail.startsWith("<") && detail.length <= 200) return detail;
    }
  }
  return `服务器未接受这次资料修改（HTTP ${status}）。`;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
