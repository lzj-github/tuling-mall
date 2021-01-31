package com.tuling.tulingmall.service.impl;

import com.github.pagehelper.PageHelper;
import com.tuling.tulingmall.common.constant.RedisKeyPrefixConst;
import com.tuling.tulingmall.component.LocalCache;
import com.tuling.tulingmall.component.zklock.ZKLock;
import com.tuling.tulingmall.dao.FlashPromotionProductDao;
import com.tuling.tulingmall.dao.PortalProductDao;
import com.tuling.tulingmall.domain.*;
import com.tuling.tulingmall.mapper.SmsFlashPromotionMapper;
import com.tuling.tulingmall.mapper.SmsFlashPromotionSessionMapper;
import com.tuling.tulingmall.model.SmsFlashPromotion;
import com.tuling.tulingmall.model.SmsFlashPromotionExample;
import com.tuling.tulingmall.model.SmsFlashPromotionSession;
import com.tuling.tulingmall.model.SmsFlashPromotionSessionExample;
import com.tuling.tulingmall.service.PmsProductService;
import com.tuling.tulingmall.util.DateUtil;
import com.tuling.tulingmall.util.RedisOpsUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 *                  ,;,,;
 *                ,;;'(    社
 *      __      ,;;' ' \   会
 *   /'  '\'~~'~' \ /'\.)  主
 * ,;(      )    /  |.     义
 *,;' \    /-.,,(   ) \    码
 *     ) /       ) / )|    农
 *     ||        ||  \)     
 *     (_\       (_\
 * @author ：杨过
 * @date ：Created in 2019/12/31 17:22
 * @version: V1.0
 * @slogan: 天下风云出我辈，一入代码岁月催
 * @description: 
 **/
@Slf4j
@Service
public class PmsProductServiceImpl implements PmsProductService {

    @Autowired
    private PortalProductDao portalProductDao;

    @Autowired
    private FlashPromotionProductDao flashPromotionProductDao;

    @Autowired
    private SmsFlashPromotionMapper flashPromotionMapper;

    @Autowired
    private SmsFlashPromotionSessionMapper promotionSessionMapper;

    @Autowired
    private RedisOpsUtil redisOpsUtil;

    private Map<String,PmsProductParam> cacheMap = new ConcurrentHashMap<>();

    @Autowired
    private LocalCache cache;

    /*
     * zk分布式锁
     */
    @Autowired
    private ZKLock zkLock;
    private String lockPath = "/load_db";

    /**
     * add by yangguo
     * 获取商品详情信息
     * @param id 产品ID
     */
    @Override
    public PmsProductParam getProductInfo(Long id){
        PmsProductParam productInfo = null;
        //不直接用map的原因，而选择google的本地内存原因是自己直接用map做内存，没有淘汰策略
        //很容易jvm内存爆满，oom异常，所以选择帮我们封装好的，有淘汰策略的本地内存
        productInfo = cache.get(RedisKeyPrefixConst.PRODUCT_DETAIL_CACHE + id);
        //一级缓存，本地缓存,比redis还快，因为通常redis在别的服务器,redis会受网络带宽,网络io限制

        //要确保最终一致性,redis更新了mysql也会更新

        //缓存是早期的做法,最优的是CDN+页面静态化
        if(productInfo != null){
            return productInfo;
        }

        //从二级缓存Redis里找
        productInfo = redisOpsUtil.get(RedisKeyPrefixConst.PRODUCT_DETAIL_CACHE+id,PmsProductParam.class);

        if(productInfo!=null){
            cache.setLocalCache(RedisKeyPrefixConst.PRODUCT_DETAIL_CACHE+id,productInfo);
            return productInfo;
        }

        //防止缓存失效时突然来了很多流里，数据库被打垮，缓存击穿,所以加了分布式锁
        //这时只允许一个人去访问我们的db，然后再把数据放到缓存中去
        if(zkLock.lock(lockPath + "_" + id)) {
            //todo 查询商品详情信息
            productInfo = portalProductDao.getProductInfo(id);
            if (productInfo == null) {
                return null;
            }

            /*
             * 查询是否是秒杀商品
             */
            FlashPromotionParam promotion = flashPromotionProductDao.getFlashPromotion(id);
            if (!ObjectUtils.isEmpty(promotion)) {
                productInfo.setFlashPromotionCount(promotion.getRelation().get(0).getFlashPromotionCount());
                productInfo.setFlashPromotionLimit(promotion.getRelation().get(0).getFlashPromotionLimit());
                productInfo.setFlashPromotionPrice(promotion.getRelation().get(0).getFlashPromotionPrice());
                productInfo.setFlashPromotionRelationId(promotion.getRelation().get(0).getId());
                productInfo.setFlashPromotionEndDate(promotion.getEndDate());
                productInfo.setFlashPromotionStartDate(promotion.getStartDate());
                productInfo.setFlashPromotionStatus(promotion.getStatus());
            }

            //todo 缓存到一级缓存
            cache.setLocalCache(RedisKeyPrefixConst.PRODUCT_DETAIL_CACHE + id, productInfo);
            //todo 商品信息缓存到reids当中，缓存被动更新
            redisOpsUtil.set(RedisKeyPrefixConst.PRODUCT_DETAIL_CACHE + id, productInfo, 3600, TimeUnit.SECONDS);
            zkLock.unlock(lockPath + "_" + id);
        }else{
            //T1拿到锁后，然后T1去unlcok锁后，删除zk上的这个临时节点
            // 删掉后在T2到Tn里，会监听到这个事件(zk节点的watch事件),这时T2到Tn就会被自动唤醒，走这个逻辑
            // 没拿到锁的去缓存找
            productInfo = redisOpsUtil.get(RedisKeyPrefixConst.PRODUCT_DETAIL_CACHE+id,PmsProductParam.class);
            if(productInfo != null){
                cache.setLocalCache(RedisKeyPrefixConst.PRODUCT_DETAIL_CACHE + id,productInfo);
            }
        }
        return productInfo;
    }

    /**
     * add by yangguo
     * 获取秒杀商品列表
     * @param flashPromotionId 秒杀活动ID，关联秒杀活动设置
     * @param sessionId 场次活动ID，for example：13:00-14:00场等
     */
    @Override
    public List<FlashPromotionProduct> getFlashProductList(Integer pageSize, Integer pageNum, Long flashPromotionId, Long sessionId){
        PageHelper.startPage(pageNum,pageSize,"sort desc");
        return flashPromotionProductDao.getFlashProductList(flashPromotionId,sessionId);
    }

    /**
     * 获取当前日期秒杀活动所有场次
     * @return
     */
    @Override
    public List<FlashPromotionSessionExt> getFlashPromotionSessionList(){
        Date now = new Date();
        SmsFlashPromotion promotion = getFlashPromotion(now);
        if(promotion != null){
            SmsFlashPromotionSessionExample sessionExample = new SmsFlashPromotionSessionExample();
            //获取时间段内的秒杀场次
            sessionExample.createCriteria().andStatusEqualTo(1);//启用状态
            sessionExample.setOrderByClause("start_time asc");
            List<SmsFlashPromotionSession> promotionSessionList = promotionSessionMapper.selectByExample(sessionExample);
            List<FlashPromotionSessionExt> extList = new ArrayList<>();
            if(!CollectionUtils.isEmpty(promotionSessionList)){
                promotionSessionList.stream().forEach((item)->{
                    FlashPromotionSessionExt ext = new FlashPromotionSessionExt();
                    BeanUtils.copyProperties(item,ext);
                    ext.setFlashPromotionId(promotion.getId());
                    if(DateUtil.getTime(now).after(DateUtil.getTime(ext.getStartTime()))
                            && DateUtil.getTime(now).before(DateUtil.getTime(ext.getEndTime()))){
                        //活动进行中
                        ext.setSessionStatus(0);
                    }else if(DateUtil.getTime(now).after(DateUtil.getTime(ext.getEndTime()))){
                        //活动即将开始
                        ext.setSessionStatus(1);
                    }else if(DateUtil.getTime(now).before(DateUtil.getTime(ext.getStartTime()))){
                        //活动已结束
                        ext.setSessionStatus(2);
                    }
                    extList.add(ext);
                });
                return extList;
            }
        }
        return null;
    }

    //根据时间获取秒杀活动
    public SmsFlashPromotion getFlashPromotion(Date date) {
        Date currDate = DateUtil.getDate(date);
        SmsFlashPromotionExample example = new SmsFlashPromotionExample();
        example.createCriteria()
                .andStatusEqualTo(1)
                .andStartDateLessThanOrEqualTo(currDate)
                .andEndDateGreaterThanOrEqualTo(currDate);
        List<SmsFlashPromotion> flashPromotionList = flashPromotionMapper.selectByExample(example);
        if (!CollectionUtils.isEmpty(flashPromotionList)) {
            return flashPromotionList.get(0);
        }
        return null;
    }

    /**
     * 获取首页的秒杀商品列表
     * @return
     */
    @Override
    public List<FlashPromotionProduct> getHomeSecKillProductList(){
        PageHelper.startPage(1,8,"sort desc");
        FlashPromotionParam flashPromotionParam = flashPromotionProductDao.getFlashPromotion(null);
        if(flashPromotionParam==null || CollectionUtils.isEmpty(flashPromotionParam.getRelation())){
            return null;
        }
        List<Long> promotionIds = new ArrayList<>();
        flashPromotionParam.getRelation().stream().forEach(item->{
            promotionIds.add(item.getId());
        });
        PageHelper.clearPage();
        return flashPromotionProductDao.getHomePromotionProductList(promotionIds);
    }

    @Override
    public CartProduct getCartProduct(Long productId) {
        return portalProductDao.getCartProduct(productId);
    }

    @Override
    public List<PromotionProduct> getPromotionProductList(List<Long> ids) {
        return portalProductDao.getPromotionProductList(ids);
    }

    /**
     * 查找出所有的产品ID
     * @return
     */
    @Override
    public List<Long> getAllProductId(){
        return portalProductDao.getAllProductId();
    }
}

