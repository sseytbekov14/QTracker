package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.service.ComponentControlsList;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The Action Centre's lists (/component/{code}, /component/ALL in place of the Controls page) read by a test: every
 * row, page after page, as the given person (a session or the request post-processors of the test class).
 */
final class ListPages {

    private ListPages() {
    }

    static ComponentControlsList.Result page(MockMvc mockMvc, UnaryOperator<MockHttpServletRequestBuilder> as,
                                             String path) throws Exception {
        MvcResult result = mockMvc.perform(as.apply(get(URI.create(path)))).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return (ComponentControlsList.Result) result.getModelAndView().getModel().get("list");
    }

    static List<ComponentControlsList.Row> rows(MockMvc mockMvc, UnaryOperator<MockHttpServletRequestBuilder> as,
                                                String path) throws Exception {
        List<ComponentControlsList.Row> rows = new ArrayList<>();
        for (int number = 1; ; number++) {
            ComponentControlsList.Result list = page(mockMvc, as,
                    path + (path.contains("?") ? "&" : "?") + "size=50&page=" + number);
            rows.addAll(list.rows());
            if (number >= list.pages()) {
                return rows;
            }
        }
    }

    static List<Long> ids(List<ComponentControlsList.Row> rows) {
        return rows.stream().map(ComponentControlsList.Row::id).toList();
    }
}
