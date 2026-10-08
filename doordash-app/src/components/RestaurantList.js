import { Card, Input, List, message, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { searchRestaurants } from "../utils";
import Cover from "./Cover";

const { Text } = Typography;

const PAGE_SIZE = 12;

// 餐厅列表：搜索框 + 卡片网格 + 分页。query（关键字、页码）由父组件保存，从餐厅详情返回时能回到原来那一页
const RestaurantList = ({ query, onQueryChange, onSelect }) => {
  const [data, setData] = useState({ items: [], total: 0 });
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // 快速翻页或连续搜索时，只采用最后一次请求的结果
    let ignore = false;
    setLoading(true);
    searchRestaurants({ keyword: query.keyword, page: query.page, size: PAGE_SIZE })
      .then((result) => {
        if (!ignore) {
          setData(result);
        }
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        if (!ignore) {
          setLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, [query]);

  return (
    <>
      <Input.Search
        placeholder="Search restaurants or categories, e.g. pizza"
        allowClear
        enterButton
        defaultValue={query.keyword}
        onSearch={(value) => onQueryChange({ keyword: value.trim(), page: 1 })}
        style={{ maxWidth: 480, marginBottom: 24 }}
      />
      <List
        loading={loading}
        grid={{ gutter: 16, xs: 1, sm: 2, md: 3, lg: 4, xl: 4, xxl: 6 }}
        dataSource={data.items}
        locale={{ emptyText: "No restaurants found" }}
        pagination={{
          current: query.page,
          pageSize: PAGE_SIZE,
          total: data.total,
          showSizeChanger: false,
          showTotal: (total) => `${total} restaurants`,
          onChange: (page) => onQueryChange({ ...query, page }),
        }}
        renderItem={(item) => (
          <List.Item>
            <Card
              hoverable
              cover={<Cover src={item.image_url} name={item.name} />}
              onClick={() => onSelect(item)}
            >
              <Card.Meta
                title={item.name}
                description={
                  <>
                    {item.category && <Tag color="blue">{item.category}</Tag>}
                    <div>
                      <Text type="secondary" ellipsis={{ tooltip: item.address }}>
                        {item.address}
                      </Text>
                    </div>
                  </>
                }
              />
            </Card>
          </List.Item>
        )}
      />
    </>
  );
};

export default RestaurantList;
