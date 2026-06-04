use sea_orm::entity::prelude::*;

#[derive(Clone, Debug, PartialEq, DeriveEntityModel)]
#[sea_orm(table_name = "spam_numbers")]
pub struct Model {
    #[sea_orm(primary_key)]
    pub id: i32,
    pub normalized_number: String,
    pub raw_number: String,
    pub label: String,
    pub list_type: String,
    pub source: String,
    pub risk_level: i32,
    pub report_count: i32,
    pub last_reported_at: DateTimeUtc,
    pub created_at: DateTimeUtc,
    pub updated_at: DateTimeUtc,
    pub notes: Option<String>,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {}

impl ActiveModelBehavior for ActiveModel {}
