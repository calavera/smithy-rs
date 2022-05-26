/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Python error definition.

use aws_smithy_types::date_time::{ConversionError, DateTimeParseError};
use pyo3::{create_exception, PyErr};
use thiserror::Error;
use tokio::task::JoinError;

create_exception!(crate, PyException, pyo3::exceptions::PyException);

/// Python error that implements foreign errors.
#[derive(Error, Debug)]
pub enum Error {
    /// Custom error.
    #[error("{0}")]
    Custom(String),
    /// Implements `From<pyo3::PyErr>`.
    #[error("PyO3: {0}")]
    PyO3(#[from] PyErr),
    /// Implements `From<tokio::task::JoinError`.
    #[error("TaskJoin: {0}")]
    TaskJoin(#[from] JoinError),
    /// Implements `From<aws_smithy_types::date_time::DateTimeParseError>`.
    #[error("DateTimeConversion: {0}")]
    DateTimeConversion(#[from] ConversionError),
    /// Implements `From<aws_smithy_types::date_time::ConversionError>`.
    #[error("DateTimeParse: {0}")]
    DateTimeParse(#[from] DateTimeParseError),
    /// Implements `From<pyo3::exception::PyException>`.
    #[error("Exception: {0}")]
    Exception(#[from] PyException),
    /// Mutex error`.
    #[error("Mutex: {0}")]
    Lock(String),
}

impl From<Error> for PyErr {
    fn from(other: Error) -> PyErr {
        PyException::new_err(other.to_string())
    }
}
