package cn.wizzer.app.sys.modules.services.impl;

import cn.wizzer.app.sys.modules.models.Sys_msg;
import cn.wizzer.app.sys.modules.models.Sys_msg_user;
import cn.wizzer.app.sys.modules.models.Sys_user;
import cn.wizzer.app.sys.modules.services.SysMsgService;
import cn.wizzer.app.sys.modules.services.SysMsgUserService;
import cn.wizzer.app.sys.modules.services.SysUserService;
import cn.wizzer.framework.base.service.BaseServiceImpl;
import cn.wizzer.framework.page.Pagination;
import com.alibaba.dubbo.config.annotation.Service;
import org.nutz.aop.interceptor.async.Async;
import org.nutz.dao.Cnd;
import org.nutz.dao.Dao;
import org.nutz.integration.jedis.RedisService;
import org.nutz.integration.jedis.pubsub.PubSubService;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.json.Json;
import org.nutz.json.JsonFormat;
import org.nutz.lang.Strings;
import org.nutz.lang.Times;
import org.nutz.lang.util.NutMap;
import org.nutz.log.Log;
import org.nutz.log.Logs;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.util.ArrayList;
import java.util.List;

@IocBean(args = {"refer:dao"})
@Service(interfaceClass = SysMsgService.class)
public class SysMsgServiceImpl extends BaseServiceImpl<Sys_msg> implements SysMsgService {
    public SysMsgServiceImpl(Dao dao) {
        super(dao);
    }

    Log log = Logs.get();

    @Inject
    private SysMsgUserService sysMsgUserService;
    @Inject
    private SysUserService sysUserService;

    @Inject
    private RedisService redisService;

    @Inject
    private PubSubService pubSubService;

    private static NutMap typeMap = NutMap.NEW().addv("system", "系统消息").addv("user", "用户消息");

    public Sys_msg saveMsg(Sys_msg sysMsg, String[] users) {
        Sys_msg dbMsg = this.insert(sysMsg);
        if (dbMsg != null) {
            if ("user".equals(dbMsg.getType()) && users != null) {
                for (String loginname : users) {
                    Sys_msg_user sys_msg_user = new Sys_msg_user();
                    sys_msg_user.setMsgId(dbMsg.getId());
                    sys_msg_user.setStatus(0);
                    sys_msg_user.setLoginname(loginname);
                    sysMsgUserService.insert(sys_msg_user);
                }
            }
            if ("system".equals(dbMsg.getType())) {
                Cnd cnd = Cnd.where("disabled", "=", false).and("delFlag", "=", false);
                int total = sysUserService.count(cnd);
                int size = 1000;
                Pagination pagination = new Pagination();
                pagination.setTotalCount(total);
                pagination.setPageSize(size);
                for (int i = 1; i <= pagination.getTotalPage(); i++) {
                    Pagination pagination2 = sysUserService.listPage(i, size, cnd);
                    for (Object sysUser : pagination2.getList()) {
                        Sys_user user = (Sys_user) sysUser;
                        Sys_msg_user sys_msg_user = new Sys_msg_user();
                        sys_msg_user.setMsgId(dbMsg.getId());
                        sys_msg_user.setStatus(0);
                        sys_msg_user.setLoginname(user.getLoginname());
                        sysMsgUserService.insert(sys_msg_user);
                    }
                }
            }
        }
        sysMsgUserService.clearCache();

        notify(dbMsg, users);
        return dbMsg;
    }

    public void deleteMsg(String id) {
        this.vDelete(id);
        sysMsgUserService.vDelete(Cnd.where("msgId", "=", id));
        sysMsgUserService.clearCache();
    }

    @Override
    @Async
    public void notify(Sys_msg innerMsg, String rooms[]) {
        String url = "/platform/sys/msg/user/all";
        if (Strings.isNotBlank(innerMsg.getUrl())) {
            url = innerMsg.getUrl();
        }
        NutMap map = new NutMap();
        map.put("action", "notify");
        map.put("title", "您有新的消息");
        map.put("body", innerMsg.getTitle());
        map.put("url", url);
        String msg = Json.toJson(map, JsonFormat.compact());
        if ("system".equals(innerMsg.getType())) {//系统消息发送给所有在线用户
            ScanParams match = new ScanParams().match("wsroom:*");
            ScanResult<String> scan = null;
            do {
                scan = redisService.scan(scan == null ? ScanParams.SCAN_POINTER_START : scan.getStringCursor(), match);
                for (String room : scan.getResult()) {
                    pubSubService.fire(room, msg);
                    getMsg(room.split(":")[1]);
                }
            } while (!scan.isCompleteIteration());
        } else if ("user".equals(innerMsg.getType())) {//用户消息发送给指定在线用户
            for (String room : rooms) {
                getMsg(room);
                ScanParams match = new ScanParams().match("wsroom:" + room + ":*");
                ScanResult<String> scan = null;
                do {
                    scan = redisService.scan(scan == null ? ScanParams.SCAN_POINTER_START : scan.getStringCursor(), match);
                    for (String key : scan.getResult()) {
                        pubSubService.fire(key, msg);
                    }
                } while (!scan.isCompleteIteration());
            }
        }
    }

    @Override
    @Async
    public void innerMsg(String room, int size, List<NutMap> list) {
        NutMap map = new NutMap();
        map.put("action", "innerMsg");
        map.put("size", size);//未读消息数
        map.put("list", list);//最新3条消息列表  type--系统消息/用户消息  title--标题  time--时间戳
        String msg = Json.toJson(map, JsonFormat.compact());
        log.debug("msg::::" + msg);
        ScanParams match = new ScanParams().match("wsroom:" + room + ":*");
        ScanResult<String> scan = null;
        do {
            scan = redisService.scan(scan == null ? ScanParams.SCAN_POINTER_START : scan.getStringCursor(), match);
            for (String key : scan.getResult()) {
                pubSubService.fire(key, msg);
            }
        } while (!scan.isCompleteIteration());
    }


    @Override
    @Async
    public void getMsg(String loginname) {
        try {
            //通过用户名查询未读消息
            int size = sysMsgUserService.getUnreadNum(loginname);
            List<Sys_msg_user> list = sysMsgUserService.getUnreadList(loginname, 1, 5);
            List<NutMap> mapList = new ArrayList<>();
            for (Sys_msg_user msgUser : list) {
                String url = "/platform/sys/msg/user/all/detail/" + msgUser.getMsgId();
                if (Strings.isNotBlank(msgUser.getMsg().getUrl())) {
                    url = msgUser.getMsg().getUrl();
                }
                mapList.add(NutMap.NEW().addv("msgId", msgUser.getMsgId()).addv("type", typeMap.getString(msgUser.getMsg().getType()))
                        .addv("title", msgUser.getMsg().getTitle())
                        .addv("url", url)
                        .addv("time", Times.format("yyyy-MM-dd HH:mm", Times.D(1000 * msgUser.getMsg().getSendAt()))));
            }
            innerMsg(loginname, size, mapList);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    @Async
    public void offline(String loginname, String httpSessionId) {
        NutMap map = new NutMap();
        map.put("action", "offline");
        map.put("title", "");
        map.put("body", "");
        map.put("url", "");
        String msg = Json.toJson(map, JsonFormat.compact());
        try {
            pubSubService.fire("wsroom:" + loginname + ":" + httpSessionId, msg);
            redisService.expire("wsroom:" + loginname + ":" + httpSessionId, 60 * 3);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

}
