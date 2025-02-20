// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "exec/parquet_scanner.h"
#include "runtime/descriptors.h"
#include "runtime/exec_env.h"
#include "runtime/mem_tracker.h"
#include "runtime/raw_value.h"
#include "runtime/stream_load/load_stream_mgr.h"
#include "runtime/stream_load/stream_load_pipe.h"
#include "runtime/tuple.h"
#include "exec/parquet_reader.h"
#include "exprs/expr.h"
#include "exec/text_converter.h"
#include "exec/text_converter.hpp"
#include "exec/local_file_reader.h"
#include "exec/broker_reader.h"
#include "exec/decompressor.h"
#include "exec/parquet_reader.h"

namespace doris {

ParquetScanner::ParquetScanner(RuntimeState* state,
                             RuntimeProfile* profile,
                             const TBrokerScanRangeParams& params,
                             const std::vector<TBrokerRangeDesc>& ranges,
                             const std::vector<TNetworkAddress>& broker_addresses,
                             ScannerCounter* counter) : BaseScanner(state, profile, params, counter),
        _ranges(ranges),
        _broker_addresses(broker_addresses),
        // _splittable(params.splittable),
        _cur_file_reader(nullptr),
        _next_range(0),
        _cur_file_eof(false),
        _scanner_eof(false) {
}

ParquetScanner::~ParquetScanner() {
    close();
}

Status ParquetScanner::open() {
    return BaseScanner::open();
}

Status ParquetScanner::get_next(Tuple* tuple, MemPool* tuple_pool, bool* eof) {
    SCOPED_TIMER(_read_timer);
    // Get one line
    while (!_scanner_eof) {
        if (_cur_file_reader == nullptr || _cur_file_eof) {
            RETURN_IF_ERROR(open_next_reader());
            // If there isn't any more reader, break this
            if (_scanner_eof) {
                continue;
            }
            _cur_file_eof = false;
        }
        RETURN_IF_ERROR(_cur_file_reader->read(_src_tuple, _src_slot_descs, tuple_pool, &_cur_file_eof));
        {
            COUNTER_UPDATE(_rows_read_counter, 1);
            SCOPED_TIMER(_materialize_timer);
            if (fill_dest_tuple(Slice(), tuple, tuple_pool)) {
                break;// break iff true
            }
        }
    }
    if (_scanner_eof) {
        *eof = true;
    } else {
        *eof = false;
    }
    return Status::OK();
}

Status ParquetScanner::open_next_reader() {
    if (_next_range >= _ranges.size()) {
        _scanner_eof = true;
        return Status::OK();
    }

    RETURN_IF_ERROR(open_file_reader());
    _next_range++;

    return Status::OK();
}

Status ParquetScanner::open_file_reader() {
    if (_cur_file_reader != nullptr) {
        if (_stream_load_pipe != nullptr) {
            _stream_load_pipe.reset();
            _cur_file_reader = nullptr;
        } else {
            delete _cur_file_reader;
            _cur_file_reader = nullptr;
        }
    }
    const TBrokerRangeDesc& range = _ranges[_next_range];
    switch (range.file_type) {
        case TFileType::FILE_LOCAL: {
            FileReader *file_reader = new LocalFileReader(range.path, range.start_offset);
            RETURN_IF_ERROR(file_reader->open());
            _cur_file_reader = new ParquetReaderWrap(file_reader);
            return _cur_file_reader->init_parquet_reader(_src_slot_descs);
        }
        case TFileType::FILE_BROKER: {
            FileReader *file_reader = new BrokerReader(_state->exec_env(), _broker_addresses, _params.properties, range.path, range.start_offset);
            RETURN_IF_ERROR(file_reader->open());
            _cur_file_reader = new ParquetReaderWrap(file_reader);
            return _cur_file_reader->init_parquet_reader(_src_slot_descs);
        }
#if 0
        case TFileType::FILE_STREAM:
        {
            _stream_load_pipe = _state->exec_env()->load_stream_mgr()->get(range.load_id);
            if (_stream_load_pipe == nullptr) {
                return Status::InternalError("unknown stream load id");
            }
            _cur_file_reader = _stream_load_pipe.get();
            break;
        }
#endif
        default: {
            std::stringstream ss;
            ss << "Unknown file type, type=" << range.file_type;
            return Status::InternalError(ss.str());
        }
    }
    return Status::OK();
}

void ParquetScanner::close() {
    if (_cur_file_reader != nullptr) {
        if (_stream_load_pipe != nullptr) {
            _stream_load_pipe.reset();
            _cur_file_reader = nullptr;
        } else {
            delete _cur_file_reader;
            _cur_file_reader = nullptr;
        }
    }
}

}
