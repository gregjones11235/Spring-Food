import { Button, Card, List, message, Space, Tag, Tooltip, Typography } from "antd";
import { ArrowLeftOutlined, PlusOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { addItemToCart, getHotItems, getMenus } from "../utils";
import Cover from "./Cover";

const { Title, Text, Paragraph } = Typography;

const AddToCartButton = ({ itemId }) => {
  const [loading, setLoading] = useState(false);

  const AddToCart = () => {
    setLoading(true);
    addItemToCart(itemId)
      .then(() => {
        message.success(`Successfully add item`);
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoading(false);
      });
  };

  return (
    <Tooltip title="Add to shopping cart">
      <Button
        loading={loading}
        type="primary"
        icon={<PlusOutlined />}
        onClick={AddToCart}
      />
    </Tooltip>
  );
};

const MenuItemCard = ({ item, extraInfo }) => (
  <Card
    title={item.name}
    extra={<AddToCartButton itemId={item.id} />}
    cover={<Cover src={item.image_url} name={item.name} height={140} />}
  >
    <Space size={8}>
      <Text strong>{`$${item.price.toFixed(2)}`}</Text>
      {extraInfo}
    </Space>
    {item.description && (
      <Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ marginTop: 8, marginBottom: 0 }}>
        {item.description}
      </Paragraph>
    )}
  </Card>
);

const GRID = { gutter: 16, xs: 1, sm: 2, md: 3, lg: 4, xl: 4, xxl: 6 };

// 餐厅详情：顶部是餐厅信息，然后是热销榜（有销量时才显示），最后是这家店的全部菜品
const RestaurantMenu = ({ restaurant, onBack }) => {
  const [foodData, setFoodData] = useState([]);
  const [hotItems, setHotItems] = useState([]);
  const [loading, setLoading] = useState(false);

  // 热销榜只是锦上添花：加载失败就不显示，不弹错误
  useEffect(() => {
    setHotItems([]);
    getHotItems(restaurant.id)
      .then(setHotItems)
      .catch(() => setHotItems([]));
  }, [restaurant.id]);

  useEffect(() => {
    setLoading(true);
    getMenus(restaurant.id)
      .then((data) => {
        setFoodData(data);
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [restaurant.id]);

  return (
    <>
      <Button icon={<ArrowLeftOutlined />} onClick={onBack} style={{ marginBottom: 16 }}>
        Back to restaurants
      </Button>
      <Card bodyStyle={{ padding: 0 }} style={{ marginBottom: 24, overflow: "hidden" }}>
        <div style={{ display: "flex", flexWrap: "wrap" }}>
          <div style={{ width: 320, maxWidth: "100%" }}>
            <Cover src={restaurant.image_url} name={restaurant.name} height={180} />
          </div>
          <div style={{ padding: "16px 24px" }}>
            <Title level={3} style={{ marginBottom: 8 }}>
              {restaurant.name}
            </Title>
            <Space direction="vertical" size={4}>
              {restaurant.category && <Tag color="blue">{restaurant.category}</Tag>}
              <Text type="secondary">{restaurant.address}</Text>
              <Text type="secondary">{restaurant.phone}</Text>
            </Space>
          </div>
        </div>
      </Card>
      {hotItems.length > 0 && (
        <>
          <Title level={4}>Most Ordered</Title>
          <List
            grid={GRID}
            dataSource={hotItems}
            renderItem={(item, index) => (
              <List.Item>
                <MenuItemCard
                  item={item}
                  extraInfo={
                    <>
                      <Tag color="volcano">{`#${index + 1}`}</Tag>
                      <Text type="secondary">{`${item.sold} sold`}</Text>
                    </>
                  }
                />
              </List.Item>
            )}
          />
          <Title level={4}>Menu</Title>
        </>
      )}
      <List
        loading={loading}
        grid={GRID}
        dataSource={foodData}
        locale={{ emptyText: "This restaurant has no menu items yet" }}
        renderItem={(item) => (
          <List.Item>
            <MenuItemCard item={item} />
          </List.Item>
        )}
      />
    </>
  );
};

export default RestaurantMenu;
