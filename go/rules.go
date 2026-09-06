// 分流规则（Go 侧权威实现）
//
// 设计原则：不硬编码海量 IP 段。
//  - 内置「极简兜底」仅覆盖电信/联通/移动/教育网几十条主干聚合段；
//  - 完整精确 GeoIP（数千条 CIDR）由 App 启动时读取 assets/cn_ipv4.txt，
//    经 nativeSetCnList() 注入替换（数据构建时自动下载，见 build_all.sh）。
package main

import (
	"net"
	"strconv"
	"strings"
	"sync"
)

const (
	outDirect = 0
	outProxy  = 1
)

type ruleEngine struct {
	mu       sync.RWMutex
	cnRanges []*net.IPNet
	loaded   bool
}

var rules = &ruleEngine{cnRanges: builtinCnNets()}

// 目标 IP 是否中国大陆
func (r *ruleEngine) isCnIP(ip net.IP) bool {
	if ip == nil {
		return false
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, n := range r.cnRanges {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// 目标域名是否国内（geosite-cn 精简）
func (r *ruleEngine) isCnDomain(domain string) bool {
	d := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(domain), "."))
	if d == "" {
		return false
	}
	for _, suf := range cnDomainSuffixes {
		if d == suf || strings.HasSuffix(d, "."+suf) {
			return true
		}
	}
	return false
}

// loadCnIPText：Java 侧注入完整 CIDR 列表（每行一个 CIDR，支持 # 注释与空行）
func (r *ruleEngine) loadCnIPText(text string) {
	var list []*net.IPNet
	for _, line := range strings.Split(text, "\n") {
		l := strings.TrimSpace(line)
		if l == "" || strings.HasPrefix(l, "#") {
			continue
		}
		if _, n, err := net.ParseCIDR(l); err == nil {
			list = append(list, n)
			continue
		}
		if ip := net.ParseIP(l); ip != nil {
			list = append(list, &net.IPNet{IP: ip, Mask: net.CIDRMask(32, 32)})
		}
	}
	if len(list) > 0 {
		r.mu.Lock()
		r.cnRanges = list
		r.loaded = true
		r.mu.Unlock()
		logLine("I", "GeoIP 完整列表已注入("+strconv.Itoa(len(list))+"段)")
	}
}

// 极简兜底：仅主干聚合（assets 缺失时使用，不影响日常分流主路径）
func builtinCnNets() []*net.IPNet {
	raw := []string{
		"1.0.1.0/24", "1.2.0.0/16", "14.16.0.0/12", "27.16.0.0/12",
		"36.0.0.0/10", "39.96.0.0/12", "42.80.0.0/13", "49.64.0.0/11",
		"58.32.0.0/12", "58.192.0.0/11", "59.64.0.0/11", "60.16.0.0/13",
		"60.160.0.0/11", "61.128.0.0/10", "101.32.0.0/12", "101.64.0.0/13",
		"103.0.0.0/16", "106.32.0.0/12", "110.96.0.0/11", "111.128.0.0/11",
		"112.0.0.0/10", "113.64.0.0/11", "114.80.0.0/12", "115.48.0.0/12",
		"116.128.0.0/10", "117.128.0.0/10", "118.32.0.0/12", "119.128.0.0/12",
		"120.32.0.0/12", "121.32.0.0/12", "122.64.0.0/11", "123.64.0.0/11",
		"124.64.0.0/13", "125.64.0.0/12", "139.200.0.0/13", "140.205.0.0/16",
		"144.0.0.0/16", "150.0.0.0/16", "153.0.0.0/16", "157.0.0.0/16",
		"159.226.0.0/16", "163.0.0.0/16", "166.111.0.0/16", "171.32.0.0/12",
		"175.48.0.0/12", "180.96.0.0/11", "182.32.0.0/12", "183.128.0.0/11",
		"202.96.0.0/12", "202.112.0.0/13", "202.128.0.0/13", "203.0.0.0/16",
		"210.0.0.0/11", "211.64.0.0/12", "211.128.0.0/12", "218.0.0.0/11",
		"219.128.0.0/11", "220.160.0.0/11", "221.0.0.0/12", "221.128.0.0/13",
		"222.32.0.0/12", "223.64.0.0/11",
	}
	nets := make([]*net.IPNet, 0, len(raw))
	for _, s := range raw {
		if _, n, err := net.ParseCIDR(s); err == nil {
			nets = append(nets, n)
		}
	}
	return nets
}

// 高频国内域名后缀（geosite-cn 精简）
var cnDomainSuffixes = []string{
	"cn", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "mil.cn",
	"baidu.com", "bdimg.com", "bdstatic.com", "baidupcs.com", "baidubce.com",
	"qq.com", "qpic.cn", "gtimg.cn", "qlogo.cn", "tencent.com", "weixin.qq.com",
	"wechat.com", "woa.com", "myapp.com",
	"taobao.com", "tmall.com", "alicdn.com", "aliyun.com", "alibaba.com",
	"alibabacloud.com", "amap.com", "dingtalk.com", "aliyuncs.com",
	"jd.com", "360buyimg.com", "jdcloud.com",
	"163.com", "126.com", "netease.com", "yeah.net",
	"sina.com", "sinaimg.cn", "sina.com.cn", "weibo.com", "weibo.cn",
	"sohu.com", "ifeng.com", "xunlei.com",
	"youku.com", "tudou.com", "iqiyi.com", "bilibili.com", "hdslb.com", "acfun.cn",
	"douyin.com", "douyincdn.com", "iesdouyin.com", "toutiao.com", "toutiaocdn.com",
	"kuaishou.com", "gifshow.com",
	"xiaohongshu.com", "xhscdn.com",
	"zhihu.com", "zhimg.com", "douban.com", "tianya.cn", "csdn.net", "cnblogs.com",
	"mi.com", "xiaomi.com", "miui.com", "mipay.com", "hupu.com",
	"ctrip.com", "qunar.com", "mafengwo.cn", "dianping.com", "meituan.com",
	"12306.cn", "chinamobile.com", "10086.cn", "189.cn", "unicom.com.cn", "10010.com",
	"cctv.com", "people.com.cn", "xinhuanet.com", "chinanews.com",
	"unionpay.com", "95516.com", "cmbchina.com", "icbc.com.cn", "ccb.com", "abchina.com",
	"bankofchina.com", "psbc.com", "boc.cn", "spdb.com.cn", "cebbank.com", "ecitic.com",
	"cmbc.com.cn", "hxb.com.cn", "bankcomm.com", "cib.com.cn", "cgbchina.com.cn",
	"pingan.com", "citicbank.com",
	"wps.cn", "kingsoft.com", "uc.cn", "ucweb.com", "sm.cn", "sogou.com", "sogoucdn.com",
	"360.cn", "360.com", "qhimg.com", "qihucdn.com",
	"dnspod.cn", "dnspod.com", "hichina.com",
	"qiyukf.com", "meiqia.com", "upyun.com", "qiniu.com", "qiniucdn.com",
	"bootcss.com", "staticfile.org", "cnzz.com", "umeng.com",
	"getui.com", "jpush.cn", "jiguang.cn",
	"fliggy.com", "ele.me", "cainiao.com",
	"dangdang.com", "suning.com", "gome.com.cn",
	"huanqiu.com", "youth.cn", "cntv.cn", "thepaper.cn",
	"oceanengine.com", "volcengine.com",
	"bcebos.com", "myqcloud.com", "qcloud.com",
}