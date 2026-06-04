use sea_orm::entity::prelude::*;

#[derive(Clone, Debug, PartialEq, DeriveEntityModel)]
#[sea_orm(table_name = "sync_events")]
pub struct Model {
    #[sea_orm(primary_key)]
    pub id: i64,
    pub entity_type: String,
    pub item_key: String,
    pub operation: String,
    pub payload: Option<Json>,
    pub created_at: DateTimeUtc,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {}

impl ActiveModelBehavior for ActiveModel {}
