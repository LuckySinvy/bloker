use sea_orm::entity::prelude::*;

#[derive(Clone, Debug, PartialEq, DeriveEntityModel)]
#[sea_orm(table_name = "spam_reports")]
pub struct Model {
    #[sea_orm(primary_key)]
    pub id: i32,
    pub normalized_number: String,
    pub raw_number: String,
    pub label: String,
    pub status: String,
    pub notes: Option<String>,
    pub review_notes: Option<String>,
    pub approved_spam_number_id: Option<i32>,
    pub created_at: DateTimeUtc,
    pub updated_at: DateTimeUtc,
    pub reviewed_at: Option<DateTimeUtc>,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {}

impl ActiveModelBehavior for ActiveModel {}
