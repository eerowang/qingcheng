package com.qingcheng.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.qingcheng.dao.BrandMapper;
import com.qingcheng.dao.SkuMapper;
import com.qingcheng.dao.SpecMapper;
import com.qingcheng.pojo.goods.Sku;
import com.qingcheng.service.goods.BrandService;
import com.qingcheng.service.goods.SkuSearchService;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import tk.mybatis.mapper.entity.Example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SkuSearchServiceImpl implements SkuSearchService {

    @Autowired
    @Qualifier("restHighLevelClient")
    private RestHighLevelClient restHighLevelClient;


    private String indexName="sku";

    private String typeName="doc";

    //????????????
    //private String fields="id,name,price,num,image,createTime,spuid,categoryName,brandName,salenum,commentnum";

    @Autowired
    private SkuMapper skuMapper;

    /**
     * ????????????SKU
     */
    /*
    public void importSkuList() {
        System.out.println("??????????????????");
        //1.?????????????????????SKU????????????
        Example example=new Example(Sku.class);
        Example.Criteria criteria = example.createCriteria();
        criteria.andEqualTo("status","1");
        List<Sku> skuList = skuMapper.selectByExample(example);
        importSkuList(skuList);
    }*/

    @Override
    public void importSkuList(List<Sku> skuList) {
        //2.??????BulkRequest
        BulkRequest bulkRequest = new BulkRequest();
        for (Sku sku : skuList) {
            if("1".equals(sku.getStatus())){
                IndexRequest indexRequest = new IndexRequest(indexName, typeName, sku.getId().toString());
                Map skuMap=new HashMap();
                skuMap.put("name",sku.getName());
                skuMap.put("brandName",sku.getBrandName());
                skuMap.put("categoryName",sku.getCategoryName());
                skuMap.put("image",sku.getImage());
                skuMap.put("price",sku.getPrice());
                skuMap.put("createTime",sku.getCreateTime());
                skuMap.put("saleNum",sku.getSaleNum());
                skuMap.put("commentNum",sku.getCommentNum());
                skuMap.put("spec",JSON.parseObject(sku.getSpec(),Map.class) );
                indexRequest.source(skuMap);
                bulkRequest.add(indexRequest);
            }
        }
        //BulkResponse BulkResponse= restHighLevelClient.bulk(bulkRequest);  ??????????????????
        //??????????????????
        restHighLevelClient.bulkAsync(bulkRequest,new ActionListener<BulkResponse>() {
            @Override
            public void onResponse(BulkResponse bulkResponse) {
                //??????
                System.out.println("????????????"+bulkResponse.status());
            }
            @Override
            public void onFailure(Exception e) {
                //??????
                System.out.println("????????????"+e.getMessage());
            }
        });
        System.out.println("????????????");
    }


    @Autowired
    private BrandService brandService;

    @Autowired
    private BrandMapper brandMapper;

    /**
     * ??????
     * @param searchMap
     * @return
     */
    public Map search(Map<String, String> searchMap){
        Map resultMap = new HashMap();//????????????
        //1.????????????
        resultMap.putAll(searchSkuList(searchMap));
        //2.????????????????????????
        List<String> categoryList = searchCategoryList(searchMap);
        resultMap.put("categoryList",categoryList);
        System.out.println("categoryList???"+categoryList.size());
        //3.??????????????????????????????
        String categoryName="";//??????????????????
        if(searchMap.get("category") == null){  //????????????????????????
            if(categoryList.size()>0){
                categoryName=categoryList.get(0);
            }
        }else{//???????????????????????????
            categoryName=searchMap.get("category");
        }
        resultMap.put("brandList",brandMapper.findListByCategoryName(categoryName));

        //4.??????????????????????????????
        resultMap.put("specList",getSpecList(categoryName));


        return resultMap;
    }


    /**
     * ????????????
     *
     * @param searchMap
     * @return
     */
    private Map searchSkuList(Map<String, String> searchMap) {
        Map resultMap = new HashMap();//????????????
        //???????????????????????? ??????????????????
        SearchRequest searchRequest = new SearchRequest(indexName);
        //?????????????????? doc
        searchRequest.types(typeName);
        // ???????????????
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        //String[] sourceFieldArray = fields.split(",");
        //?????????????????????????????????????????????,?????????????????????
        //searchSourceBuilder.fetchSource(sourceFieldArray, new String[]{});
        //???????????????????????????
        BoolQueryBuilder boolQueryBuilder = buildBasicQuery(searchMap);
        //????????????
        searchSourceBuilder.query(boolQueryBuilder);

        //??????
        Integer pageNo = Integer.parseInt(searchMap.get("pageNo"));//??????
        if(pageNo<=0){
            pageNo = 1;
        }
        Integer pageSize = 30;//?????????
        //??????????????????
        int fromIndex = (pageNo - 1) * pageSize;
        searchSourceBuilder.from(fromIndex);//????????????
        searchSourceBuilder.size(pageSize);//?????????

        //??????
        String sortRule = searchMap.get("sortRule");// ???????????? ASC  DESC
        String sortField = searchMap.get("sortField");//????????????  price
        if (sortField != null && !"".equals(sortField)) {//?????????
            searchSourceBuilder.sort(SortBuilders.fieldSort(sortField).order(SortOrder.valueOf(sortRule)));
        }

        //????????????
        HighlightBuilder highlightBuilder = new HighlightBuilder();
        highlightBuilder.preTags("<font style='color:red'>");
        highlightBuilder.postTags("</font>");
        highlightBuilder.fields().add(new HighlightBuilder.Field("name"));
        searchSourceBuilder.highlighter(highlightBuilder);

        searchRequest.source(searchSourceBuilder);
        try {
            //????????????
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest);
            //??????????????????
            SearchHits searchHits = searchResponse.getHits();
            //??????????????????
            List<Map<String, Object>> skuContentList = getSkuContent(searchHits);
            resultMap.put("rows", skuContentList);

            //??????????????????
            long totalCount = searchHits.getTotalHits();//????????????
            long pageCount = (totalCount % pageSize == 0) ? totalCount / pageSize : (totalCount / pageSize + 1);
            resultMap.put("totalPages", pageCount);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return resultMap;
    }


    /**
     * ??????????????????
     *
     * @param searchMap
     * @return
     */
    private BoolQueryBuilder buildBasicQuery(Map<String, String> searchMap) {
        // ??????????????????
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        //1.???????????????
        queryBuilder.must(QueryBuilders.matchQuery("name", searchMap.get("keywords")));

        //2.??????????????????
        if(searchMap.get("category")!=null){
            queryBuilder.filter(QueryBuilders.matchQuery("categoryName", searchMap.get("category")));
        }

        //3.????????????
        if (searchMap.get("brand") != null) {
            queryBuilder.filter(QueryBuilders.matchQuery("brandName", searchMap.get("brand")));
        }

        //4.????????????
        for (String key : searchMap.keySet()) {
            if (key.startsWith("spec.")) {//?????????????????????
                queryBuilder.filter(QueryBuilders.matchQuery(key , searchMap.get(key)));
                //queryBuilder.filter(QueryBuilders.matchQuery("spec." + key.substring(5) , searchMap.get(key)));
            }
        }

        //5.????????????
        if(searchMap.get("price")!=null){
            String[] price = ((String)searchMap.get("price")).split("-");
            if(!price[0].equals("0")){//?????????????????????0
                queryBuilder.filter(QueryBuilders.rangeQuery("price").gt(price[0]+"00"));
            }
            if(!price[1].equals("*")){//?????????????????????
                queryBuilder.filter(QueryBuilders.rangeQuery("price").lt(price[1]+"00"));
            }
        }

        return queryBuilder;
    }


    //??????????????????
    private List<Map<String, Object>> getSkuContent(SearchHits searchHits) {
        SearchHit[] searchHit = searchHits.getHits();
        List<Map<String, Object>> resultList = new ArrayList<Map<String, Object>>();
        for (SearchHit hit : searchHits) {
            //?????????????????????
            Map<String, Object> skuMap = hit.getSourceAsMap();
            //??????????????????
            String name ="";
            Map<String, HighlightField> highlightFields = hit.getHighlightFields();
            if(highlightFields!=null) {
                HighlightField highlightFieldName = highlightFields.get("name");
                if (highlightFieldName != null) {
                    Text[] fragments = highlightFieldName.fragments();
                    name = fragments[0].toString();
                }
            }
            skuMap.put("name",name); //????????????
            resultList.add(skuMap);
        }
        return resultList;
    }


    /**
     * ??????????????????
     * @param searchMap
     * @return
     */
    private List<String> searchCategoryList(Map searchMap) {
        //????????????????????????
        SearchRequest searchRequest = new SearchRequest(indexName);
        //??????????????????
        searchRequest.types(typeName);
        //????????????
        BoolQueryBuilder boolQueryBuilder = buildBasicQuery(searchMap);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        String groupName = "sku_category";
        //??????????????????
        TermsAggregationBuilder aggregation = AggregationBuilders.terms(groupName).field("categoryName.keyword");
        searchSourceBuilder.aggregation(aggregation);
        searchSourceBuilder.size(0);
        //????????????
        searchSourceBuilder.query(boolQueryBuilder);
        searchRequest.source(searchSourceBuilder);

        List<String> categoryList = null;
        try {
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest);
            Aggregations aggregations = searchResponse.getAggregations();
            Map<String, Aggregation> asMap = aggregations.getAsMap();
            Terms terms = (Terms) asMap.get(groupName);
            categoryList = terms.getBuckets().stream().map(bucket -> bucket.getKeyAsString()).collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return categoryList;
    }

    @Autowired
    private SpecMapper specMapper;

    /**
     * ??????????????????
     * @param categoryName
     * @return
     */
    private List<Map> getSpecList(String categoryName){
        List<Map> specList = specMapper.findListByCategoryName(categoryName);
        for(Map spec:specList){
            String[] options = spec.get("options").toString().split(",");
            spec.put("options", options);
        }
        return specList;
    }


}
