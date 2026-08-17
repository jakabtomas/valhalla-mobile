#include <boost/property_tree/ptree.hpp>
#include <valhalla/tyr/actor.h>
#include <valhalla/baldr/rapidjson_utils.h>
#include <valhalla/loki/worker.h>
#include "valhalla_actor.h"

#include <stdexcept>
#include <utility>

class TileGetterWrapper : public valhalla::baldr::tile_getter_t {
public:
  /**
   * @param pool_size  the number of curler instances in the pool
   * @param user_agent  user agent to use by curlers for HTTP requests
   * @param gzipped  whether to request for gzip compressed data
   * @param user_pw  the "user:pwd" for HTTP basic auth
   */
  TileGetterWrapper(std::unique_ptr<ValhallaMobileHttpClient> http_client, bool is_gzipped)
      : is_gzipped(is_gzipped), http_client(std::move(http_client)) {
  }

  GET_response_t get(const std::string& url,
                     const uint64_t range_offset = 0,
                     const uint64_t range_size = 0) override {
    GET_response_t result;
    if (http_client) { 
        result = http_client->get(url, range_offset, range_size);
    } else {
        result.status_ = tile_getter_t::status_code_t::FAILURE;
    }
    return result;
  }

  HEAD_response_t head(const std::string& url, header_mask_t header_mask) override {
    HEAD_response_t result;
    if (http_client) { 
        result = http_client->head(url, header_mask);
    } else {
        result.status_ = tile_getter_t::status_code_t::FAILURE;
    }
    return result;
  }

  bool gzipped() const override {
    return is_gzipped;
  }

private:
  bool is_gzipped;
  std::unique_ptr<ValhallaMobileHttpClient> http_client;
};


ValhallaActor::ValhallaActor(
    const std::string& config_path,
    std::unique_ptr<ValhallaMobileHttpClient> http_client) {
std::string config_file(config_path);
    
    // Set up the config object
    boost::property_tree::ptree config;
    rapidjson::read_json(config_file, config);

    auto mjolnir_config = config.get_child("mjolnir");
    std::unique_ptr<TileGetterWrapper> tile_getter;
    if (!mjolnir_config.get<std::string>("tile_url", std::string()).empty()) {
      if (!http_client) {
        throw std::invalid_argument("A tile URL requires an HTTP client");
      }
      tile_getter = std::make_unique<TileGetterWrapper>(
          std::move(http_client), mjolnir_config.get<bool>("tile_url_gz", false));
    }
    graph_reader = std::make_unique<valhalla::baldr::GraphReader>(
      mjolnir_config, std::move(tile_getter));
    // Setup the actor
    actor = std::make_unique<valhalla::tyr::actor_t>(config, *graph_reader, true);
}

std::string ValhallaActor::route(const std::string& request) {
    // Convert the request to a std::string
    std::string req = std::string(request);
    
    // Produce the route result
    std::string result = actor->route(req);
    
    return result;
}

std::string ValhallaActor::trace_attributes(const std::string& request) {
    return actor->trace_attributes(request);
}
